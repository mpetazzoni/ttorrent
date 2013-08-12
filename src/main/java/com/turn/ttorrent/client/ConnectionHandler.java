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
import com.turn.ttorrent.client.peer.SharingPeerInfo;
import com.turn.ttorrent.common.ConnectionUtils;
import com.turn.ttorrent.common.Peer;
import com.turn.ttorrent.common.Torrent;
import com.turn.ttorrent.common.TorrentHash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.*;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.text.ParseException;
import java.util.HashSet;
import java.util.Set;
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
public class ConnectionHandler implements Runnable {

  private static final Logger logger =
    LoggerFactory.getLogger(ConnectionHandler.class);

  public static final int PORT_RANGE_START = 6881;
  public static final int PORT_RANGE_END = 6989;

  private static final int OUTBOUND_CONNECTIONS_POOL_SIZE = 20;
  private static final int OUTBOUND_CONNECTIONS_THREAD_KEEP_ALIVE_SECS = 10;

	private static final int CLIENT_KEEP_ALIVE_MINUTES = 3;

  private final ConcurrentMap<String, SharedTorrent> torrents;
  private String id;
	private ServerSocketChannel myServerSocketChannel;
  private InetSocketAddress address;

  private final Set<CommunicationListener> listeners;
  private ThreadPoolExecutor executor;
  private Thread thread;
  private volatile boolean stop;

  /**
   * Create and start a new listening service for out torrent, reporting
   * with our peer ID on the given address.
   * <p/>
   * <p>
   * This binds to the first available port in the client port range
   * PORT_RANGE_START to PORT_RANGE_END.
   * </p>
   *
   * @param torrents The torrents shared by this client.
   * @param id       This client's peer ID.
   * @param address  The address to bind to.
   * @throws IOException When the service can't be started because no port in
   *                     the defined range is available or usable.
   */
  ConnectionHandler(ConcurrentMap<String, SharedTorrent> torrents, String id, InetAddress address)
    throws IOException {
    this.torrents = torrents;
    this.id = id;

    // Bind to the first available port in the range
    // [PORT_RANGE_START; PORT_RANGE_END].
    for (int port = ConnectionHandler.PORT_RANGE_START;
         port <= ConnectionHandler.PORT_RANGE_END;
         port++) {
      InetSocketAddress tryAddress =
        new InetSocketAddress(address, port);

      try {
        this.myServerSocketChannel = ServerSocketChannel.open();
        this.myServerSocketChannel.socket().bind(tryAddress);
        this.myServerSocketChannel.configureBlocking(false);
        if (!this.myServerSocketChannel.socket().isBound()) {
          myServerSocketChannel.close();
          continue;
        }
        if (!this.myServerSocketChannel.socket().isBound()) {
          this.myServerSocketChannel.socket().bind(tryAddress);
          if (!this.myServerSocketChannel.socket().isBound()) {
            System.out.println("Here");
          }
        }
        this.address = tryAddress;
        break;
      } catch (IOException ioe) {
        // Ignore, try next port
				logger.warn("Could not bind to {}, trying next port...", tryAddress);
      }
    }

	if (this.myServerSocketChannel == null || !this.myServerSocketChannel.socket().isBound()) {
      throw new IOException("No available port for the BitTorrent client!");
    }
    logger.info("Listening for incoming connections on {}.", this.address);

    this.listeners = new HashSet<CommunicationListener>();
    this.executor = null;
    this.thread = null;
  }

  /**
   * Return the full socket address this service is bound to.
   */
  public InetSocketAddress getSocketAddress() {
    return this.address;
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
      if (this.myServerSocketChannel == null) {
          throw new IllegalStateException(
                  "Connection handler cannot be recycled!");
      }

      this.stop = false;

    if (this.executor == null || this.executor.isShutdown()) {
      this.executor = new ThreadPoolExecutor(
        OUTBOUND_CONNECTIONS_POOL_SIZE,
        OUTBOUND_CONNECTIONS_POOL_SIZE,
        OUTBOUND_CONNECTIONS_THREAD_KEEP_ALIVE_SECS,
        TimeUnit.SECONDS,
        new LinkedBlockingQueue<Runnable>(),
        new ConnectorThreadFactory());
    }

    if (this.thread == null || !this.thread.isAlive()) {
      this.thread = new Thread(this);
      this.thread.setName("bt-serve");
      this.thread.start();
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
    this.stop = true;

    if (this.thread != null && this.thread.isAlive()) {
      try {
        this.thread.join();
			} catch (InterruptedException ie) {
				Thread.currentThread().interrupt();
      }
    }

    if (this.executor != null && !this.executor.isShutdown()) {
      this.executor.shutdownNow();
    }

    this.executor = null;
    this.thread = null;
  }


  /**
   * Close this connection handler to release the port it is bound to.
   *
   * @throws IOException If the channel could not be closed.
   */
  public void close() throws IOException {
      if (this.myServerSocketChannel != null) {
          this.myServerSocketChannel.close();
          this.myServerSocketChannel = null;
      }
  }

    /**
   * The main service loop.
   * <p/>
   * <p>
	 * The service waits for new connections for 250ms, then waits 100ms so it
   * can be interrupted.
   * </p>
   */
  @Override
  public void run() {
    while (!this.stop) {
      try {
        SocketChannel client = this.myServerSocketChannel.accept();
        if (client != null) {
          int recvBufferSize = 65536;
          try {
            recvBufferSize = Integer.parseInt(System.getProperty("torrent.recv.buffer.size", "65536"));
          } catch (NumberFormatException ne){
          }
          client.socket().setReceiveBufferSize(recvBufferSize);
          int sendBufferSize = 65536;
          try {
            sendBufferSize = Integer.parseInt(System.getProperty("torrent.send.buffer.size", "65536"));
          } catch (NumberFormatException ne){
          }
          client.socket().setSendBufferSize(sendBufferSize);
          // this actually doesn't work in 1.6. Waiting for 1.7
          client.socket().setPerformancePreferences(0, 0, 1);
          this.accept(client);
        }
      } catch (SocketTimeoutException ste) {
        // Ignore and go back to sleep
      } catch (IOException ioe) {
				logger.warn("Unrecoverable error in connection handler", ioe);
        this.stop();
      }

      try {
        Thread.sleep(100);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
      }
    }
  }


  /**
   * Accept the next incoming connection.
   * <p/>
   * <p>
   * When a new peer connects to this service, wait for it to send its
   * handshake. We then parse and check that the handshake advertises the
   * torrent hash we expect, then reply with our own handshake.
   * </p>
   * <p/>
   * <p>
   * If everything goes according to plan, notify the
   * <code>CommunicationListener</code>s with the connected socket and
   * the parsed peer ID.
   * </p>
   */
  private void accept(SocketChannel clientChannel)
    throws IOException {
    final String socketRepresentation = ConnectionUtils.socketRepr(clientChannel);
    try {
      logger.debug("New incoming connection from {}, waiting for handshake...", socketRepresentation);
      Handshake hs = ConnectionUtils.validateHandshake(clientChannel, null);
      if (!torrents.containsKey(hs.getHexInfoHash())) {
        throw new ParseException("Handshake for unknow torrent " +
                Torrent.byteArrayToHexString(hs.getInfoHash()) +
                " from " + ConnectionUtils.socketRepr(clientChannel) + ".", hs.getPstrlen() + 9);
      }

      logger.trace("Validated handshake from {}. Identifier: {}", socketRepresentation, hs.getTorrentIdentifier());
      int sentBytes = ConnectionUtils.sendHandshake(clientChannel, hs.getInfoHash(), id.getBytes(Torrent.BYTE_ENCODING));
      logger.trace("Replied to {} with handshake ({} bytes).", socketRepresentation, sentBytes);

      // Go to non-blocking mode for peer interaction
      clientChannel.configureBlocking(false);
      clientChannel.socket().setSoTimeout(CLIENT_KEEP_ALIVE_MINUTES * 60 * 1000);
      for (CommunicationListener listener : this.listeners) {
        listener.handleNewPeerConnection(clientChannel, hs.getPeerId(), hs.getHexInfoHash());
      }
    } catch (ParseException pe) {
      logger.info("Invalid handshake from {}: {}", socketRepresentation, pe.getMessage());
      logger.error(pe.getMessage(), pe);
      try {
        clientChannel.close();
      } catch (IOException e) {
      }
    } catch (IOException ioe) {
      logger.info("An error occured while reading an incoming " +
        "handshake: {}", ioe.getMessage());
      try {
        if (clientChannel.isConnected()) {
          clientChannel.close();
        }
      } catch (IOException e) {
        // Ignore
      }
    }
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

    Future curTask = peer.getConnectTask();
    if (executor.getQueue().size() > 0) return;

    Future connectTask = this.executor.submit(new ConnectorTask(this, peer));
    peer.setConnectTask(connectTask);

  }

  /**
   * A simple thread factory that returns appropriately named threads for
   * outbound connector threads.
   *
   * @author mpetazzoni
   */
  private static class ConnectorThreadFactory implements ThreadFactory {

    private int number = 0;

    @Override
    public Thread newThread(Runnable r) {
      Thread t = new Thread(r);
      t.setName("bt-connect-" + ++this.number);
      return t;
    }
  }

  ;


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
    private final Peer myPeer;
    private TorrentHash myTorrentHash;
    private byte[] mySelfId;

    private ConnectorTask(ConnectionHandler handler, SharingPeer peer) {
      this.myListeners = handler.listeners;
      this.myPeer = peer;
      this.myTorrentHash = peer.getTorrentHash();
      try {
        this.mySelfId = handler.id.getBytes(Torrent.BYTE_ENCODING);
      } catch (UnsupportedEncodingException e) {

      }
    }

    @Override
    public void run() {
      ConnectionUtils.connect(myPeer, mySelfId, myTorrentHash, myListeners);
    }
  };
}
