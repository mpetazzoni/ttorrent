/**
 * Copyright (C) 2011-2012 Turn, Inc.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.turn.ttorrent.client;

import com.turn.ttorrent.Constants;
import com.turn.ttorrent.client.announce.*;
import com.turn.ttorrent.client.network.CountLimitConnectionAllower;
import com.turn.ttorrent.client.network.OutgoingConnectionListener;
import com.turn.ttorrent.client.network.StateChannelListener;
import com.turn.ttorrent.client.peer.PeerActivityListener;
import com.turn.ttorrent.client.peer.SharingPeer;
import com.turn.ttorrent.common.*;
import com.turn.ttorrent.common.protocol.AnnounceRequestMessage;
import com.turn.ttorrent.common.protocol.PeerMessage;
import com.turn.ttorrent.network.*;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.turn.ttorrent.Constants.DEFAULT_SOCKET_CONNECTION_TIMEOUT_MILLIS;
import static com.turn.ttorrent.common.protocol.AnnounceRequestMessage.RequestEvent.*;

/**
 * A pure-java BitTorrent client.
 * <p/>
 * <p>
 * A BitTorrent client in its bare essence shares a given torrent. If the
 * torrent is not complete locally, it will continue to download it. If or
 * after the torrent is complete, the client may eventually continue to seed it
 * for other clients.
 * </p>
 * <p/>
 * <p>
 * This BitTorrent client implementation is made to be simple to embed and
 * simple to use. First, initialize a ShareTorrent object from a torrent
 * meta-info source (either a file or a byte array, see
 * com.turn.ttorrent.SharedTorrent for how to create a SharedTorrent object).
 * </p>
 *
 * @author mpetazzoni
 */
public class Client implements AnnounceResponseListener, PeerActivityListener, Context, ConnectionManagerContext {

  protected static final Logger logger = TorrentLoggerFactory.getLogger();

  private static final int MAX_DOWNLOADERS_UNCHOKE = 10;

  public static final String BITTORRENT_ID_PREFIX = "-TO0042-";

  private AtomicBoolean stop = new AtomicBoolean(false);

  private Announce announce;

  private Random random;
  private volatile boolean myStarted = false;
  private final TorrentLoader myTorrentLoader;
  private final TorrentsStorage torrentsStorage;
  private final CountLimitConnectionAllower myInConnectionAllower;
  private final CountLimitConnectionAllower myOutConnectionAllower;
  private final AtomicInteger mySendBufferSize;
  private final AtomicInteger myReceiveBufferSize;
  private final PeersStorage peersStorage;
  private volatile ConnectionManager myConnectionManager;
  private final ExecutorService myExecutorService;
  private final ExecutorService myPieceValidatorExecutor;

  /**
   * @param workingExecutor        executor service for run connection worker and process incoming data. Must have a pool size at least 2
   * @param pieceValidatorExecutor executor service for calculation sha1 hashes of downloaded pieces
   */
  public Client(ExecutorService workingExecutor, ExecutorService pieceValidatorExecutor) {
    this(workingExecutor, pieceValidatorExecutor, new TrackerClientFactoryImpl());
  }

  /**
   * @param workingExecutor        executor service for run connection worker and process incoming data. Must have a pool size at least 2
   * @param pieceValidatorExecutor executor service for calculation sha1 hashes of downloaded pieces
   * @param trackerClientFactory   factory which creates instances for communication with tracker
   */
  public Client(ExecutorService workingExecutor, ExecutorService pieceValidatorExecutor, TrackerClientFactory trackerClientFactory) {
    this.random = new Random(System.currentTimeMillis());
    this.announce = new Announce(this, trackerClientFactory);
    this.torrentsStorage = new TorrentsStorage();
    this.peersStorage = new PeersStorage();
    this.mySendBufferSize = new AtomicInteger();
    this.myTorrentLoader = new TorrentLoaderImpl(this.torrentsStorage);
    this.myReceiveBufferSize = new AtomicInteger();
    this.myInConnectionAllower = new CountLimitConnectionAllower(peersStorage);
    this.myOutConnectionAllower = new CountLimitConnectionAllower(peersStorage);
    this.myExecutorService = workingExecutor;
    myPieceValidatorExecutor = pieceValidatorExecutor;
  }

  /**
   * Adds torrent to storage, validate downloaded files and start seeding and leeching the torrent
   *
   * @param dotTorrentFilePath path to torrent metadata file
   * @param downloadDirPath    path to directory where downloaded files are placed
   * @return hash of added torrent
   * @throws IOException              if IO error occurs in reading metadata file
   * @throws NoSuchAlgorithmException if the SHA-1 algorithm is not available.
   */
  public String addTorrent(String dotTorrentFilePath, String downloadDirPath) throws IOException, NoSuchAlgorithmException {
    return addTorrent(dotTorrentFilePath, downloadDirPath, false, false);
  }

  /**
   * Adds torrent to storage and start seeding without validation
   *
   * @param dotTorrentFilePath path to torrent metadata file
   * @param downloadDirPath    path to directory where downloaded files are placed
   * @return hash of added torrent
   * @throws IOException              if IO error occurs in reading metadata file
   * @throws NoSuchAlgorithmException if the SHA-1 algorithm is not available.
   */
  public String seedTorrent(String dotTorrentFilePath, String downloadDirPath) throws IOException, NoSuchAlgorithmException {
    return addTorrent(dotTorrentFilePath, downloadDirPath, true, false);
  }

  String addTorrent(String dotTorrentFilePath, String downloadDirPath, boolean seeder, boolean leecher) throws IOException, NoSuchAlgorithmException {
    TorrentMultiFileMetadata torrent = new TorrentParser().parseFromFile(new File(dotTorrentFilePath));
    final AnnounceableTorrentImpl announceableTorrent = new AnnounceableTorrentImpl(
            new TorrentStatistic(),
            torrent.getHexInfoHash(),
            torrent.getInfoHash(),
            torrent.getAnnounceList(),
            torrent.getAnnounce(),
            downloadDirPath,
            dotTorrentFilePath,
            seeder,
            leecher);
    this.torrentsStorage.addAnnounceableTorrent(torrent.getHexInfoHash(), announceableTorrent);

    if (seeder) {
      announceableTorrent.getTorrentStatistic().setLeft(0);
    } else {
      long size = 0;
      for (TorrentFile torrentFile : torrent.getFiles()) {
        size += torrentFile.size;
      }
      announceableTorrent.getTorrentStatistic().setLeft(size);
    }

    forceAnnounceAndLogError(announceableTorrent, seeder ? COMPLETED : STARTED, announceableTorrent.getDotTorrentFilePath());
    logger.debug(String.format("Added torrent %s (%s)", torrent, torrent.getHexInfoHash()));
    return torrent.getHexInfoHash();
  }

  private void forceAnnounceAndLogError(AnnounceableTorrent torrent, AnnounceRequestMessage.RequestEvent event,
                                        String dotTorrentFilePath) {
    try {
      this.announce.forceAnnounce(torrent, this, event);
    } catch (IOException e) {
      logger.warn("unable to force announce torrent {}. Dot torrent path is {}", torrent.getHexInfoHash(), dotTorrentFilePath);
      logger.debug("", e);
    }
  }

  /**
   * Removes specified torrent from storage.
   *
   * @param torrentHash specified torrent hash
   */
  public void removeTorrent(String torrentHash) {
    logger.debug("Stopping seeding " + torrentHash);
    final Pair<SharedTorrent, AnnounceableFileTorrent> torrents = torrentsStorage.remove(torrentHash);

    SharedTorrent torrent = torrents.first();
    if (torrent != null) {
      torrent.setClientState(ClientState.DONE);
      torrent.close();
    } else {
      logger.warn(String.format("Torrent %s already removed from myTorrents", torrentHash));
    }
    sendStopEvent(torrents.second(), torrentHash);
  }

  private void removeAndDeleteTorrent(String torrentHash, SharedTorrent torrent) {
    final Pair<SharedTorrent, AnnounceableFileTorrent> torrents = torrentsStorage.remove(torrentHash);
    final SharedTorrent sharedTorrent = torrents.first() == null ? torrent : torrents.first();
    if (sharedTorrent != null) {
      sharedTorrent.setClientState(ClientState.DONE);
      sharedTorrent.delete();
    }
    sendStopEvent(torrents.second(), torrentHash);
  }

  private void sendStopEvent(AnnounceableFileTorrent announceableFileTorrent, String torrentHash) {
    if (announceableFileTorrent == null) {
      logger.info("Announceable torrent {} not found in storage after unsuccessful download attempt", torrentHash);
      return;
    }
    forceAnnounceAndLogError(announceableFileTorrent, STOPPED, announceableFileTorrent.getDotTorrentFilePath());
  }

  /**
   * set specified announce interval between requests to the tracker
   *
   * @param announceInterval announce interval in seconds
   */
  public void setAnnounceInterval(final int announceInterval) {
    announce.setAnnounceInterval(announceInterval);
  }

  /**
   * Return the torrent this client is exchanging on.
   */
  public Collection<SharedTorrent> getTorrents() {
    return this.torrentsStorage.activeTorrents();
  }

  @SuppressWarnings("unused")
  public SharedTorrent getTorrentByFilePath(File file) {
    String path = file.getAbsolutePath();
    for (SharedTorrent torrent : torrentsStorage.activeTorrents()) {
      File parentFile = torrent.getParentFile();
      final List<String> filenames = TorrentUtils.getTorrentFileNames(torrent);
      for (String filename : filenames) {
        File seededFile = new File(parentFile, filename);
        if (seededFile.getAbsolutePath().equals(path)) {
          return torrent;
        }
      }
    }
    return null;
  }

  @SuppressWarnings("unused")
  public URI getDefaultTrackerURI() {
    return announce.getDefaultTrackerURI();
  }

  /**
   * Returns the set of known peers.
   */
  public Set<SharingPeer> getPeers() {
    return new HashSet<SharingPeer>(this.peersStorage.getSharingPeers());
  }

  public void setMaxInConnectionsCount(int maxConnectionsCount) {
    this.myInConnectionAllower.setMyMaxConnectionCount(maxConnectionsCount);
  }

  /**
   * set ups new receive buffer size, that will be applied to all new connections.
   * If value is equal or less, than zero, then method doesn't have effect
   *
   * @param newSize new size
   */
  public void setReceiveBufferSize(int newSize) {
    myReceiveBufferSize.set(newSize);
  }

  /**
   * set ups new send buffer size, that will be applied to all new connections.
   * If value is equal or less, than zero, then method doesn't have effect
   *
   * @param newSize new size
   */
  public void setSendBufferSize(int newSize) {
    mySendBufferSize.set(newSize);
  }

  public void setMaxOutConnectionsCount(int maxConnectionsCount) {
    this.myOutConnectionAllower.setMyMaxConnectionCount(maxConnectionsCount);
  }

  /**
   * Runs client instance and starts announcing, seeding and downloading of all torrents from storage
   *
   * @param bindAddresses list of addresses which are used for sending to the tracker. Current client
   *                      must be available for other peers on the addresses
   * @throws IOException if any io error occurs
   */
  public void start(final InetAddress... bindAddresses) throws IOException {
    start(bindAddresses, Constants.DEFAULT_ANNOUNCE_INTERVAL_SEC, null, new SelectorFactoryImpl());
  }

  /**
   * Runs client instance and starts announcing, seeding and downloading of all torrents from storage
   *
   * @param bindAddresses     list of addresses which are used for sending to the tracker. Current client
   *                          must be available for other peers on the addresses
   * @param defaultTrackerURI default tracker address.
   *                          All torrents will be announced not only on the trackers from metadata file but also to this tracker
   * @throws IOException if any io error occurs
   */
  public void start(final InetAddress[] bindAddresses, final URI defaultTrackerURI) throws IOException {
    start(bindAddresses, Constants.DEFAULT_ANNOUNCE_INTERVAL_SEC, defaultTrackerURI, new SelectorFactoryImpl());
  }

  public Peer[] getSelfPeers(final InetAddress[] bindAddresses) throws UnsupportedEncodingException {
    Peer self = peersStorage.getSelf();

    if (self == null) {
      return new Peer[0];
    }

    Peer[] result = new Peer[bindAddresses.length];
    for (int i = 0; i < bindAddresses.length; i++) {
      final InetAddress bindAddress = bindAddresses[i];
      final Peer peer = new Peer(new InetSocketAddress(bindAddress.getHostAddress(), self.getPort()));
      peer.setTorrentHash(self.getHexInfoHash());
      //if we have more, that one bind address, then only for first set self peer id. For other generate it
      if (i == 0) {
        peer.setPeerId(self.getPeerId());
      } else {
        final String id = Client.BITTORRENT_ID_PREFIX + UUID.randomUUID().toString().split("-")[4];
        byte[] idBytes = id.getBytes(Constants.BYTE_ENCODING);
        peer.setPeerId(ByteBuffer.wrap(idBytes));
      }
      result[i] = peer;
    }
    return result;
  }

  /**
   * Runs client instance and starts announcing, seeding and downloading of all torrents from storage
   *
   * @param bindAddresses       list of addresses which are used for sending to the tracker. Current client
   *                            must be available for other peers on the addresses
   * @param announceIntervalSec default announce interval. This interval can be override by tracker
   * @param defaultTrackerURI   default tracker address.
   *                            All torrents will be announced not only on the trackers from metadata file but also to this tracker
   * @param selectorFactory     factory for creating {@link java.nio.channels.Selector} instance.
   * @throws IOException if any io error occurs
   */
  public void start(final InetAddress[] bindAddresses,
                    final int announceIntervalSec,
                    final URI defaultTrackerURI,
                    final SelectorFactory selectorFactory) throws IOException {
    this.myConnectionManager = new ConnectionManager(
            this,
            new SystemTimeService(),
            myInConnectionAllower,
            myOutConnectionAllower,
            selectorFactory,
            mySendBufferSize,
            myReceiveBufferSize);
    this.setSocketConnectionTimeout(DEFAULT_SOCKET_CONNECTION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
    try {
      this.myConnectionManager.initAndRunWorker();
    } catch (IOException e) {
      LoggerUtils.errorAndDebugDetails(logger, "error in initialization server channel", e);
      this.stop();
      return;
    }
    final String id = Client.BITTORRENT_ID_PREFIX + UUID.randomUUID().toString().split("-")[4];
    byte[] idBytes = id.getBytes(Constants.BYTE_ENCODING);
    Peer self = new Peer(new InetSocketAddress(myConnectionManager.getBindPort()), ByteBuffer.wrap(idBytes));
    peersStorage.setSelf(self);
    logger.info("BitTorrent client [{}] started and " +
                    "listening at {}:{}...",
            new Object[]{
                    self.getShortHexPeerId(),
                    self.getIp(),
                    self.getPort()
            });

    announce.start(defaultTrackerURI, this, getSelfPeers(bindAddresses), announceIntervalSec);
    this.stop.set(false);

    myStarted = true;
  }

  /**
   * Immediately but gracefully stop this client.
   */
  public void stop() {
    this.stop(60, TimeUnit.SECONDS);
  }

  void stop(int timeout, TimeUnit timeUnit) {
    boolean wasStopped = this.stop.getAndSet(true);
    if (wasStopped) return;

    if (!myStarted)
      return;

    this.myConnectionManager.close();

    logger.trace("try stop announce thread...");

    this.announce.stop();

    logger.trace("announce thread is stopped");

    for (SharedTorrent torrent : this.torrentsStorage.activeTorrents()) {
      logger.trace("try close torrent {}", torrent);
      torrent.close();
      if (torrent.isFinished()) {
        torrent.setClientState(ClientState.DONE);
      } else {
        torrent.setClientState(ClientState.ERROR);
      }
    }

    logger.debug("Closing all remaining peer connections...");
    for (SharingPeer peer : this.peersStorage.getSharingPeers()) {
      peer.unbind(true);
    }

    torrentsStorage.clear();
    logger.info("BitTorrent client signing off.");
  }

  public void setCleanupTimeout(int timeout, TimeUnit timeUnit) throws IllegalStateException {
    ConnectionManager connectionManager = this.myConnectionManager;
    if (connectionManager == null) {
      throw new IllegalStateException("connection manager is null");
    }
    connectionManager.setCleanupTimeout(timeUnit.toMillis(timeout));
  }

  public void setSocketConnectionTimeout(int timeout, TimeUnit timeUnit) throws IllegalStateException {
    ConnectionManager connectionManager = this.myConnectionManager;
    if (connectionManager == null) {
      throw new IllegalStateException("connection manager is null");
    }
    connectionManager.setSocketConnectionTimeout(timeUnit.toMillis(timeout));
  }

  /**
   * Tells whether we are a seed for the torrent we're sharing.
   */
  public boolean isSeed(String hexInfoHash) {
    SharedTorrent t = this.torrentsStorage.getTorrent(hexInfoHash);
    return t != null && t.isComplete();
  }

  /**
   * Starts downloading of specified torrent. This method blocks until downloading will be finished or some error occurs
   *
   * @param dotTorrentPath         path to torrent metadata file
   * @param downloadDirPath        path to directory where downloaded files are placed
   * @param downloadTimeoutSeconds timeout in seconds for downloading one piece of data (size of piece depends from the torrent)
   * @throws IOException              if IO error occurs in reading metadata file or downloading was failed
   * @throws InterruptedException     if download was interrupted
   * @throws NoSuchAlgorithmException if the SHA-1 algorithm is not available.
   */
  public void downloadUninterruptibly(final String dotTorrentPath,
                                      final String downloadDirPath,
                                      final long downloadTimeoutSeconds) throws IOException, InterruptedException, NoSuchAlgorithmException {
    downloadUninterruptibly(dotTorrentPath, downloadDirPath, downloadTimeoutSeconds, 1, new AtomicBoolean(false), 5000);
  }

  /**
   * Starts downloading of specified torrent. This method blocks until downloading will be finished or some error occurs
   *
   * @param dotTorrentPath      path to torrent metadata file
   * @param downloadDirPath     path to directory where downloaded files are placed
   * @param idleTimeoutSec      timeout in seconds for downloading of one piece of data (size of piece depends from the torrent)
   * @param minSeedersCount     minimum count of seeders for the torrent. If seeders are not enough IO error will be thrown
   * @param isInterrupted       atomic boolean instance which can be used for interrupt current download from other thread
   * @param maxTimeForConnectMs maximum time for set up connections with peers.
   * @throws IOException              if IO error occurs in reading metadata file or downloading was failed
   * @throws InterruptedException     if download was interrupted
   * @throws NoSuchAlgorithmException if the SHA-1 algorithm is not available.
   */
  public void downloadUninterruptibly(final String dotTorrentPath,
                                      final String downloadDirPath,
                                      final long idleTimeoutSec,
                                      final int minSeedersCount,
                                      final AtomicBoolean isInterrupted,
                                      final long maxTimeForConnectMs) throws IOException, InterruptedException, NoSuchAlgorithmException {
    downloadUninterruptibly(dotTorrentPath, downloadDirPath, idleTimeoutSec, minSeedersCount, isInterrupted,
            maxTimeForConnectMs, new DownloadProgressListener.NopeListener());
  }

  /**
   * Starts downloading of specified torrent. This method blocks until downloading will be finished or some error occurs
   *
   * @param dotTorrentPath      path to torrent metadata file
   * @param downloadDirPath     path to directory where downloaded files are placed
   * @param idleTimeoutSec      timeout in seconds for downloading of one piece of data (size of piece depends from the torrent)
   * @param minSeedersCount     minimum count of seeders for the torrent. If seeders are not enough IO error will be thrown
   * @param isInterrupted       atomic boolean instance which can be used for interrupt current download from other thread
   * @param maxTimeForConnectMs maximum time for set up connections with peers.
   * @param listener            listener for monitoring download progress
   * @throws IOException              if IO error occurs in reading metadata file or downloading was failed
   * @throws InterruptedException     if download was interrupted
   * @throws NoSuchAlgorithmException if the SHA-1 algorithm is not available.
   */
  public void downloadUninterruptibly(final String dotTorrentPath,
                                      final String downloadDirPath,
                                      final long idleTimeoutSec,
                                      final int minSeedersCount,
                                      final AtomicBoolean isInterrupted,
                                      final long maxTimeForConnectMs,
                                      DownloadProgressListener listener) throws IOException, InterruptedException, NoSuchAlgorithmException {
    String hash = addTorrent(dotTorrentPath, downloadDirPath, false, true);

    final AnnounceableFileTorrent announceableTorrent = torrentsStorage.getAnnounceableTorrent(hash);
    if (announceableTorrent == null)
      throw new IOException("Unable to download torrent completely - announceable torrent is not found");
    SharedTorrent torrent = new TorrentLoaderImpl(torrentsStorage).loadTorrent(announceableTorrent);

    long maxIdleTime = System.currentTimeMillis() + idleTimeoutSec * 1000;
    torrent.addDownloadProgressListener(listener);
    final long startDownloadAt = System.currentTimeMillis();
    long currentLeft = torrent.getLeft();

    while (torrent.getClientState() != ClientState.SEEDING &&
            torrent.getClientState() != ClientState.ERROR &&
            (torrent.getSeedersCount() >= minSeedersCount || torrent.getLastAnnounceTime() < 0) &&
            (System.currentTimeMillis() <= maxIdleTime)) {
      if (torrent.isFinished()) break;
      if (Thread.currentThread().isInterrupted() || isInterrupted.get())
        throw new InterruptedException("Download of " + torrent.getDirectoryName() + " was interrupted");
      if (currentLeft > torrent.getLeft()) {
        currentLeft = torrent.getLeft();
        maxIdleTime = System.currentTimeMillis() + idleTimeoutSec * 1000;
      }
      if (System.currentTimeMillis() - startDownloadAt > maxTimeForConnectMs) {
        if (getPeersForTorrent(torrent.getHexInfoHash()).size() < minSeedersCount) {
          break;
        }
      }
      Thread.sleep(100);
    }

    if (!(torrent.isFinished() && torrent.getClientState() == ClientState.SEEDING)) {
      removeAndDeleteTorrent(hash, torrent);

      final List<SharingPeer> peersForTorrent = getPeersForTorrent(hash);
      int connectedPeersForTorrent = peersForTorrent.size();
      for (SharingPeer peer : peersForTorrent) {
        peer.unbind(true);
      }

      final String errorMsg;
      if (System.currentTimeMillis() > maxIdleTime) {
        int completedPieces = torrent.getCompletedPieces().cardinality();
        int totalPieces = torrent.getPieceCount();
        errorMsg = String.format("No pieces has been downloaded in %d seconds. Downloaded pieces %d/%d, connected peers %d"
                , idleTimeoutSec, completedPieces, totalPieces, connectedPeersForTorrent);
      } else if (connectedPeersForTorrent < minSeedersCount) {
        errorMsg = String.format("Not enough seeders. Required %d, found %d", minSeedersCount, connectedPeersForTorrent);
      } else if (torrent.getClientState() == ClientState.ERROR) {
        errorMsg = "Torrent state is ERROR";
      } else {
        errorMsg = "Unknown error";
      }
      throw new IOException("Unable to download torrent completely - " + errorMsg);
    }
  }

  public List<SharingPeer> getPeersForTorrent(String torrentHash) {
    if (torrentHash == null) return new ArrayList<SharingPeer>();

    List<SharingPeer> result = new ArrayList<SharingPeer>();
    for (SharingPeer sharingPeer : peersStorage.getSharingPeers()) {
      if (torrentHash.equals(sharingPeer.getHexInfoHash())) {
        result.add(sharingPeer);
      }
    }
    return result;
  }

  public boolean isRunning() {
    return myStarted;
  }

  private Collection<SharingPeer> getConnectedPeers() {
    Set<SharingPeer> result = new HashSet<SharingPeer>();
    Set<SharingPeer> toRemove = new HashSet<SharingPeer>();
    for (SharingPeer peer : this.peersStorage.getSharingPeers()) {
      if (peer.isConnected()) {
        result.add(peer);
      } else {
        toRemove.add(peer);
      }
    }
    for (SharingPeer peer : toRemove) {
      this.peersStorage.removeSharingPeer(peer);
    }
    return result;
  }

  /**
   * @param hash specified torrent hash
   * @return true if storage contains specified torrent. False otherwise
   * @see TorrentsStorage#hasTorrent
   */
  @SuppressWarnings("unused")
  public boolean containsTorrentWithHash(String hash) {
    return torrentsStorage.hasTorrent(hash);
  }

  @Override
  public PeersStorage getPeersStorage() {
    return peersStorage;
  }

  @Override
  public TorrentsStorage getTorrentsStorage() {
    return torrentsStorage;
  }

  @Override
  public ExecutorService getExecutor() {
    return myExecutorService;
  }

  public ExecutorService getPieceValidatorExecutor() {
    return myPieceValidatorExecutor;
  }

  @Override
  public ConnectionListener newChannelListener() {
    return new StateChannelListener(this);
  }

  @Override
  public SharingPeer createSharingPeer(String host, int port, ByteBuffer peerId, SharedTorrent torrent, ByteChannel channel) {
    return new SharingPeer(host, port, peerId, torrent, getConnectionManager(), this, channel);
  }

  @Override
  public TorrentLoader getTorrentLoader() {
    return myTorrentLoader;
  }


  /** AnnounceResponseListener handler(s). **********************************/

  /**
   * Handle an announce response event.
   *
   * @param interval   The announce interval requested by the tracker.
   * @param complete   The number of seeders on this torrent.
   * @param incomplete The number of leechers on this torrent.
   */
  @Override
  public void handleAnnounceResponse(int interval, int complete, int incomplete, String hexInfoHash) {
    final SharedTorrent sharedTorrent = this.torrentsStorage.getTorrent(hexInfoHash);
    if (sharedTorrent != null) {
      sharedTorrent.setSeedersCount(complete);
      sharedTorrent.setLastAnnounceTime(System.currentTimeMillis());
    }
    setAnnounceInterval(interval);
  }

  /**
   * Handle the discovery of new peers.
   *
   * @param peers The list of peers discovered (from the announce response or
   *              any other means like DHT/PEX, etc.).
   */
  @Override
  public void handleDiscoveredPeers(List<Peer> peers, String hexInfoHash) {

    if (peers.size() == 0) return;

    SharedTorrent torrent = torrentsStorage.getTorrent(hexInfoHash);

    if (torrent != null && torrent.isFinished()) return;

    final AnnounceableFileTorrent announceableTorrent = torrentsStorage.getAnnounceableTorrent(hexInfoHash);
    if (announceableTorrent == null) {
      logger.info("announceable torrent {} is not found in storage. Maybe it was removed", hexInfoHash);
      return;
    }

    if (announceableTorrent.isSeeded()) return;

    logger.info("Got {} peer(s) ({}) for {} in tracker response", new Object[]{peers.size(),
            Arrays.toString(peers.toArray()), hexInfoHash});

    Map<PeerUID, Peer> uniquePeers = new HashMap<PeerUID, Peer>();
    for (Peer peer : peers) {
      final PeerUID peerUID = new PeerUID(peer.getAddress(), hexInfoHash);
      if (uniquePeers.containsKey(peerUID)) continue;
      uniquePeers.put(peerUID, peer);
    }

    for (Map.Entry<PeerUID, Peer> e : uniquePeers.entrySet()) {

      PeerUID peerUID = e.getKey();
      Peer peer = e.getValue();
      boolean alreadyConnectedToThisPeer = peersStorage.getSharingPeer(peerUID) != null;

      if (alreadyConnectedToThisPeer) {
        logger.debug("skipping peer {}, because we already connected to this peer", peer);
        continue;
      }

      ConnectionListener connectionListener = new OutgoingConnectionListener(
              this,
              announceableTorrent,
              peer.getIp(),
              peer.getPort());

      logger.debug("trying to connect to the peer {}", peer);

      boolean connectTaskAdded = this.myConnectionManager.offerConnect(
              new ConnectTask(peer.getIp(),
                      peer.getPort(),
                      connectionListener,
                      new SystemTimeService().now(),
                      Constants.DEFAULT_CONNECTION_TIMEOUT_MILLIS), 1, TimeUnit.SECONDS);
      if (!connectTaskAdded) {
        logger.info("can not connect to peer {}. Unable to add connect task to connection manager", peer);
      }
    }
  }

  /**
   * PeerActivityListener handler(s). *************************************
   */

  @Override
  public void handlePeerChoked(SharingPeer peer) { /* Do nothing */ }

  @Override
  public void handlePeerReady(SharingPeer peer) { /* Do nothing */ }

  @Override
  public void handlePieceAvailability(SharingPeer peer,
                                      Piece piece) { /* Do nothing */ }

  @Override
  public void handleBitfieldAvailability(SharingPeer peer,
                                         BitSet availablePieces) { /* Do nothing */ }

  @Override
  public void handlePieceSent(SharingPeer peer,
                              Piece piece) { /* Do nothing */ }

  /**
   * Piece download completion handler.
   * <p/>
   * <p>
   * When a piece is completed, and valid, we announce to all connected peers
   * that we now have this piece.
   * </p>
   * <p/>
   * <p>
   * We use this handler to identify when all of the pieces have been
   * downloaded. When that's the case, we can start the seeding period, if
   * any.
   * </p>
   *
   * @param peer  The peer we got the piece from.
   * @param piece The piece in question.
   */
  @Override
  public void handlePieceCompleted(final SharingPeer peer, final Piece piece)
          throws IOException {
    final SharedTorrent torrent = peer.getTorrent();
    final String torrentHash = torrent.getHexInfoHash();
    try {
      final Future<?> validationFuture = myPieceValidatorExecutor.submit(new Runnable() {
        @Override
        public void run() {
          validatePieceAsync(torrent, piece, torrentHash, peer);
        }
      });
      torrent.markCompletedAndAddValidationFuture(piece, validationFuture);
    } catch (RejectedExecutionException e) {
      torrent.markUncompleted(piece);
      LoggerUtils.warnWithMessageAndDebugDetails(logger, "Unable to submit validation task for torrent {}", torrentHash, e);
    }
  }

  private void validatePieceAsync(final SharedTorrent torrent, final Piece piece, String torrentHash, SharingPeer peer) {
    try {
      synchronized (piece) {
        piece.validate(torrent, piece);
        if (piece.isValid()) {
          // Send a HAVE message to all connected peers, which don't have the piece
          PeerMessage have = PeerMessage.HaveMessage.craft(piece.getIndex());
          for (SharingPeer remote : getConnectedPeers()) {
            if (remote.getTorrent().getHexInfoHash().equals(torrentHash) &&
                    !remote.getAvailablePieces().get(piece.getIndex()))
              remote.send(have);
          }

          final boolean isTorrentComplete;
          synchronized (torrent) {
            torrent.removeValidationFuture(piece);
            // Make sure the piece is marked as completed in the torrent
            // Note: this is required because the order the
            // PeerActivityListeners are called is not defined, and we
            // might be called before the torrent's piece completion
            // handler is.
            logger.trace("Completed download and validation of {} from {}, now has {}/{} pieces.",
                    new Object[]{
                            piece,
                            peer,
                            torrent.getCompletedPieces().cardinality(),
                            torrent.getPieceCount()
                    });

            boolean isCurrentPeerSeeder = peer.getAvailablePieces().cardinality() == torrent.getPieceCount();
            //if it's seeder we will send not interested message when we download full file
            if (!isCurrentPeerSeeder) {
              if (torrent.isAllPiecesOfPeerCompletedAndValidated(peer)) {
                peer.notInteresting();
              }
            }

            isTorrentComplete = torrent.isComplete();

            if (isTorrentComplete) {
              logger.debug("Download of {} complete.", torrent.getDirectoryName());

              torrent.finish();
            }
          }

          if (isTorrentComplete) {

            AnnounceableTorrent announceableTorrent = torrentsStorage.getAnnounceableTorrent(torrentHash);

            if (announceableTorrent == null) return;

            try {
              announce.getCurrentTrackerClient(announceableTorrent)
                      .announceAllInterfaces(COMPLETED, true, announceableTorrent);
            } catch (AnnounceException e) {
              logger.debug("unable to announce torrent {} on tracker {}", torrent, torrent.getAnnounce());
            }

            for (SharingPeer remote : getPeersForTorrent(torrentHash)) {
              remote.notInteresting();
            }

          }
        } else {
          torrent.markUncompleted(piece);
          logger.info("Downloaded piece #{} from {} was not valid ;-(. Trying another peer", piece.getIndex(), peer);
          peer.getPoorlyAvailablePieces().set(piece.getIndex());
        }
      }
    } catch (Throwable e) {
      torrent.markUncompleted(piece);
      LoggerUtils.warnWithMessageAndDebugDetails(logger, "unhandled exception in piece {} validation task", piece, e);
    }
  }

  @Override
  public void handlePeerDisconnected(SharingPeer peer) {
    Peer p = new Peer(peer.getIp(), peer.getPort());
    p.setPeerId(peer.getPeerId());
    p.setTorrentHash(peer.getHexInfoHash());
    logger.trace("Peer {} disconnected, [{}/{}].",
            new Object[]{
                    peer,
                    getConnectedPeers().size(),
                    this.peersStorage.getSharingPeers().size()
            });
  }

  @Override
  public void afterPeerRemoved(SharingPeer peer) {
    logger.trace("disconnected peer " + peer);
    torrentsStorage.peerDisconnected(peer.getHexInfoHash());
  }

  @Override
  public void handleIOException(SharingPeer peer, IOException ioe) {
    logger.debug("I/O problem occured when reading or writing piece data for peer {}: {}.", peer, ioe.getMessage());

    peer.unbind(true);
  }

  @Override
  public void handleNewPeerConnected(SharingPeer peer) {
    //do nothing
  }

  public ConnectionManager getConnectionManager() throws IllegalStateException {
    ConnectionManager connectionManager = this.myConnectionManager;
    if (connectionManager == null) {
      throw new IllegalStateException("connection manager is null");
    }
    return connectionManager;
  }
}
