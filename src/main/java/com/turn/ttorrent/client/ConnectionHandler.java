/**
 * Copyright (C) 2011-2012 Turn, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.turn.ttorrent.client;

import com.turn.ttorrent.client.peer.SharingPeer;
import com.turn.ttorrent.common.ConnectionUtils;
import com.turn.ttorrent.common.Peer;
import com.turn.ttorrent.common.TorrentHash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.channels.SocketChannel;
import java.util.*;
import java.util.concurrent.*;


/**
 * Incoming peer connections service.
 * <p/>
 * <p>
 * Every BitTorrent client, BitTorrent being a peer-to-peer protocol, listens
 * on a port for incoming connections from other peers sharing the same
 * torrent.
 * </p>
 * <p/>
 * <p>
 * This ConnectionHandler implements this service and starts a listening socket
 * in the first available port in the default BitTorrent client port range
 * 6881-6889. When a peer connects to it, it expects the BitTorrent handshake
 * message, parses it and replies with our own handshake.
 * </p>
 * <p/>
 * <p>
 * Outgoing connections to other peers are also made through this service,
 * which handles the handshake procedure with the remote peer. Regardless of
 * the direction of the connection, once this handshake is successful, all
 * {@link CommunicationListener}s are notified and passed the connected
 * socket and the remote peer ID.
 * </p>
 * <p/>
 * <p>
 * This class does nothing more. All further peer-to-peer communication happens
 * in the {@link com.turn.ttorrent.client.peer.PeerExchange PeerExchange}
 * class.
 * </p>
 *
 * @author mpetazzoni
 * @see <a href="http://wiki.theory.org/BitTorrentSpecification#Handshake">BitTorrent handshake specification</a>
 */
public class ConnectionHandler implements TorrentConnectionListener {

  private static final Logger logger =
          LoggerFactory.getLogger(ConnectionHandler.class);

  private static final int OUTBOUND_CONNECTIONS_CORE_POOL_SIZE = 1;
  private static final int OUTBOUND_CONNECTIONS_MAX_POOL_SIZE = 20;
  private static final int OUTBOUND_CONNECTIONS_THREAD_KEEP_ALIVE_SECS = 60;
  private static final int MAX_CONNECTIONS_REQUESTS_POOL_SIZE = 100;


  private final ConcurrentMap<String, SharedTorrent> torrents;

  private final Set<CommunicationListener> listeners;
  private ThreadPoolExecutor executor;
  private final Client client;

  /**
   * Create and start a new listening service for out torrent, reporting
   * with our peer ID on the given address.
   * <p/>
   * <p>
   * This binds to the first available port in the client port range
   * PORT_RANGE_START to PORT_RANGE_END.
   * </p>
   *
   * @param torrents  The torrents shared by this client.
   * @param addresses The address to bind to.
   * @param client
   * @throws IOException When the service can't be started because no port in
   *                     the defined range is available or usable.
   */
  ConnectionHandler(ConcurrentMap<String, SharedTorrent> torrents, InetAddress[] addresses, Client client)
          throws IOException {
    this.torrents = torrents;
    this.client = client;

    this.listeners = new HashSet<CommunicationListener>();
    this.executor = null;

  }

  /**
   * Register a new incoming connection listener.
   *
   * @param listener The listener who wants to receive connection
   *                 notifications.
   */
  public void register(CommunicationListener listener) {
    this.listeners.add(listener);
  }

  /**
   * Start accepting new connections in a background thread.
   */
  public void start() {

    if (this.executor == null || this.executor.isShutdown()) {
      final LinkedBlockingQueue<Runnable> workQueue = new LinkedBlockingQueue<Runnable>(MAX_CONNECTIONS_REQUESTS_POOL_SIZE);
      this.executor = new ThreadPoolExecutor(
              OUTBOUND_CONNECTIONS_CORE_POOL_SIZE,
              OUTBOUND_CONNECTIONS_MAX_POOL_SIZE,
              OUTBOUND_CONNECTIONS_THREAD_KEEP_ALIVE_SECS,
              TimeUnit.SECONDS,
              workQueue,
              new ConnectorThreadFactory());
    }
  }

  /**
   * Stop accepting connections.
   * <p/>
   * <p>
   * <b>Note:</b> the underlying socket remains open and bound.
   * </p>
   */
  public void stop() {
    if (this.executor != null && !this.executor.isShutdown()) {
      this.executor.shutdownNow();
    }
    this.executor = null;
  }

  /**
   * Tells whether the connection handler is running and can be used to
   * handle new peer connections.
   */
  public boolean isAlive() {
    return this.executor != null &&
            !this.executor.isShutdown() &&
            !this.executor.isTerminated();
  }

  /**
   * Connect to the given peer and perform the BitTorrent handshake.
   * <p/>
   * <p>
   * Submits an asynchronous connection task to the outbound connections
   * executor to connect to the given peer.
   * </p>
   *
   * @param peer The peer to connect to.
   */
  public void connect(SharingPeer peer) {
    if (!this.isAlive()) {
      throw new IllegalStateException(
              "Connection handler is not accepting new peers at this time!");
    }
    Future connectTask = this.executor.submit(new ConnectorTask(this, peer, client));
    peer.setConnectTask(connectTask);
  }

  @Override
  public boolean hasTorrent(TorrentHash torrentHash) {
    return torrents.get(torrentHash.getHexInfoHash()) != null;
  }

  @Override
  public void handleNewPeerConnection(SocketChannel s, byte[] peerId, String hexInfoHash) {
    for (CommunicationListener listener : listeners) {
      listener.handleNewPeerConnection(s, peerId, hexInfoHash);
    }
  }

  /**
   * A simple thread factory that returns appropriately named threads for
   * outbound connector threads.
   *
   * @author mpetazzoni
   */
  private static class ConnectorThreadFactory implements ThreadFactory {

    private volatile int number = 0;

    @Override
    public Thread newThread(Runnable r) {
      Thread t = new Thread(r);
      t.setName("bt-connect-" + ++this.number);
      return t;
    }
  }


  /**
   * An outbound connection task.
   * <p/>
   * <p>
   * These tasks are fed to the thread executor in charge of processing
   * outbound connection requests. It attempts to connect to the given peer
   * and proceeds with the BitTorrent handshake. If the handshake is
   * successful, the new peer connection event is fired to all incoming
   * connection listeners. Otherwise, the failed connection event is fired.
   * </p>
   *
   * @author mpetazzoni
   */
  private static class ConnectorTask implements Runnable {

    private final Set<CommunicationListener> myListeners;
    private final Peer myConnectPeer;
    private TorrentHash myTorrentHash;
    private Map<InetAddress, byte[]> mySelfIdCandidates;

    private ConnectorTask(ConnectionHandler handler, SharingPeer connectPeer, Client client) {
      this.myListeners = handler.listeners;
      this.myConnectPeer = connectPeer;
      this.myTorrentHash = connectPeer.getTorrentHash();
      mySelfIdCandidates = new HashMap<InetAddress, byte[]>();
      for (Peer selfPeer : client.getSelfPeers()) {
        mySelfIdCandidates.put(selfPeer.getAddress(), selfPeer.getPeerIdArray());
      }
    }

    @Override
    public void run() {
      ConnectionUtils.connect(myConnectPeer, mySelfIdCandidates, myTorrentHash, myListeners);
    }
  }
}
