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
import com.turn.ttorrent.client.announce.Announce;
import com.turn.ttorrent.client.announce.AnnounceException;
import com.turn.ttorrent.client.announce.AnnounceResponseListener;
import com.turn.ttorrent.client.network.CountLimitConnectionAllower;
import com.turn.ttorrent.client.network.OutgoingConnectionListener;
import com.turn.ttorrent.client.network.StateChannelListener;
import com.turn.ttorrent.client.peer.PeerActivityListener;
import com.turn.ttorrent.client.peer.SharingPeer;
import com.turn.ttorrent.common.*;
import com.turn.ttorrent.common.protocol.AnnounceRequestMessage;
import com.turn.ttorrent.common.protocol.PeerMessage;
import com.turn.ttorrent.network.ConnectTask;
import com.turn.ttorrent.network.ConnectionListener;
import com.turn.ttorrent.network.ConnectionManager;
import com.turn.ttorrent.network.ConnectionManagerContext;
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
import java.util.concurrent.Executors;
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
public class Client implements AnnounceResponseListener, PeerActivityListener, TorrentStateListener, Context, ConnectionManagerContext {

  protected static final Logger logger = TorrentLoggerFactory.getLogger();

  /**
   * Peers unchoking frequency, in seconds. Current BitTorrent specification
   * recommends 10 seconds to avoid choking fibrilation.
   */
  private static final int UNCHOKING_FREQUENCY = 3;

  /**
   * Optimistic unchokes are done every 2 loop iterations, i.e. every
   * 2*UNCHOKING_FREQUENCY seconds.
   */
  private static final int OPTIMISTIC_UNCHOKE_ITERATIONS = 3;

  private static final int RATE_COMPUTATION_ITERATIONS = 2;
  private static final int MAX_DOWNLOADERS_UNCHOKE = 10;

  /**
   * Default data output directory.
   */
  private static final String DEFAULT_OUTPUT_DIRECTORY = "/tmp";

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
   * @param executorService executor service for run connection worker and process incoming data. Must have a pool size at least 2
   */
  public Client(ExecutorService executorService) {
    this.random = new Random(System.currentTimeMillis());
    this.announce = new Announce(this);
    this.torrentsStorage = new TorrentsStorage();
    this.peersStorage = new PeersStorage();
    this.mySendBufferSize = new AtomicInteger();
    this.myTorrentLoader = new TorrentLoaderImpl(this.torrentsStorage);
    this.myReceiveBufferSize = new AtomicInteger();
    this.myInConnectionAllower = new CountLimitConnectionAllower(peersStorage);
    this.myOutConnectionAllower = new CountLimitConnectionAllower(peersStorage);
    this.myExecutorService = executorService;
    myPieceValidatorExecutor = Executors.newFixedThreadPool(4);
  }

  public String addTorrent(String dotTorrentFilePath, String downloadDirPath) throws IOException, InterruptedException, NoSuchAlgorithmException {
    return addTorrent(dotTorrentFilePath, downloadDirPath, false, false);
  }

  public String addTorrent(String dotTorrentFilePath, String downloadDirPath, boolean seeder, boolean leecher) throws IOException, InterruptedException, NoSuchAlgorithmException {
    Torrent torrent = Torrent.load(new File(dotTorrentFilePath));
    final AnnounceableTorrentImpl announceableTorrent = new AnnounceableTorrentImpl(
            new TorrentStatistic(),
            torrent.getHexInfoHash(),
            torrent.getInfoHash(),
            torrent.getAnnounceList(),
            torrent.getAnnounce(),
            downloadDirPath,
            dotTorrentFilePath,
            seeder);
    this.torrentsStorage.addAnnounceableTorrent(torrent.getHexInfoHash(), announceableTorrent);

    if (seeder) {
      announceableTorrent.getTorrentStatistic().setLeft(0);
    } else {
      announceableTorrent.getTorrentStatistic().setLeft(torrent.getSize());
    }

    forceAnnounceAndLogError(announceableTorrent, seeder ? COMPLETED : STARTED, announceableTorrent.getDotTorrentFilePath());
    logger.debug(String.format("Added torrent %s (%s)", torrent.getName(), torrent.getHexInfoHash()));
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

  public void removeTorrent(TorrentHash torrentHash) {
    logger.debug("Stopping seeding " + torrentHash.getHexInfoHash());
    final Pair<SharedTorrent, AnnounceableFileTorrent> torrents = torrentsStorage.remove(torrentHash.getHexInfoHash());

    SharedTorrent torrent = torrents.first();
    if (torrent != null) {
      torrent.setClientState(ClientState.DONE);
      torrent.close();
    } else {
      logger.warn(String.format("Torrent %s already removed from myTorrents", torrentHash.getHexInfoHash()));
    }
    sendStopEvent(torrents.second(), torrentHash.getHexInfoHash());
  }

  public void removeAndDeleteTorrent(String torrentHash, SharedTorrent torrent) {
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

  public void setAnnounceInterval(final int announceInterval) {
    announce.setAnnounceInterval(announceInterval);
  }

  /**
   * Return the torrent this client is exchanging on.
   */
  public Collection<SharedTorrent> getTorrents() {
    return this.torrentsStorage.activeTorrents();
  }

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

  public void start(final InetAddress... bindAddresses) throws IOException {
    start(bindAddresses, Constants.DEFAULT_ANNOUNCE_INTERVAL_SEC, null);
  }

  public void start(final InetAddress[] bindAddresses, final URI defaultTrackerURI) throws IOException {
    start(bindAddresses, Constants.DEFAULT_ANNOUNCE_INTERVAL_SEC, defaultTrackerURI);
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
        byte[] idBytes = id.getBytes(Torrent.BYTE_ENCODING);
        peer.setPeerId(ByteBuffer.wrap(idBytes));
      }
      result[i] = peer;
    }
    return result;
  }

  public void start(final InetAddress[] bindAddresses, final int announceIntervalSec, final URI defaultTrackerURI) throws IOException {
    this.myConnectionManager = new ConnectionManager(
            this,
            new SystemTimeService(),
            myInConnectionAllower,
            myOutConnectionAllower,
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
    byte[] idBytes = id.getBytes(Torrent.BYTE_ENCODING);
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

  public void stop(int timeout, TimeUnit timeUnit) {
    boolean wasStopped = this.stop.getAndSet(true);
    if (wasStopped) return;

    if (!myStarted)
      return;

    this.myConnectionManager.close();

    logger.trace("try stop announce thread...");

    this.announce.stop();

    logger.trace("announce thread is stopped");

    myPieceValidatorExecutor.shutdownNow();

    if (timeout > 0) {
      boolean terminatedSuccessfully;
      try {
        terminatedSuccessfully = myPieceValidatorExecutor.awaitTermination(timeout, timeUnit);
      } catch (InterruptedException e) {
        terminatedSuccessfully = false;
      }
      if (!terminatedSuccessfully) {
        logger.warn("Unable to await termination of piece validator executor in {} {}", timeout, timeUnit);
      }
    }

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

  public void downloadUninterruptibly(final String dotTorrentPath,
                                      final String downloadDirPath,
                                      final long downloadTimeoutSeconds) throws IOException, InterruptedException, NoSuchAlgorithmException {
    downloadUninterruptibly(dotTorrentPath, downloadDirPath, downloadTimeoutSeconds, 1, new AtomicBoolean(false), 5000);
  }

  public void downloadUninterruptibly(final String dotTorrentPath,
                                      final String downloadDirPath,
                                      final long idleTimeoutSec,
                                      final int minSeedersCount,
                                      final AtomicBoolean isInterrupted,
                                      final long maxTimeForConnectMs) throws IOException, InterruptedException, NoSuchAlgorithmException {
    downloadUninterruptibly(dotTorrentPath, downloadDirPath, idleTimeoutSec, minSeedersCount, isInterrupted,
            maxTimeForConnectMs, new DownloadProgressListener.NopeListener());
  }

  public void downloadUninterruptibly(final String dotTorrentPath,
                                      final String downloadDirPath,
                                      final long idleTimeoutSec,
                                      final int minSeedersCount,
                                      final AtomicBoolean isInterrupted,
                                      final long maxTimeForConnectMs,
                                      DownloadProgressListener listener) throws IOException, InterruptedException, NoSuchAlgorithmException {
    String hash = addTorrent(dotTorrentPath, downloadDirPath, false, true);

    final AnnounceableFileTorrent announceableTorrent = torrentsStorage.getAnnounceableTorrent(hash);
    if (announceableTorrent == null) throw new IOException("Unable to download torrent completely - announceable torrent is not found");
    final SharedTorrent torrent = SharedTorrent.fromFile(new File(dotTorrentPath),
            new File(downloadDirPath),
            false,
            false,
            true,
            announceableTorrent);
    torrentsStorage.putIfAbsentActiveTorrent(torrent.getHexInfoHash(), torrent);

    long maxIdleTime = System.currentTimeMillis() + idleTimeoutSec * 1000;
      torrent.addDownloadProgressListener(listener);
      final long startDownloadAt = System.currentTimeMillis();
      long currentLeft = torrent.getLeft();

      while (torrent.getClientState() != ClientState.SEEDING &&
              torrent.getClientState() != ClientState.ERROR &&
              (torrent.getSeedersCount() >= minSeedersCount || torrent.getLastAnnounceTime() < 0) &&
              (System.currentTimeMillis() <= maxIdleTime)) {
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

  /**
   * Display information about the BitTorrent client state.
   * <p/>
   * <p>
   * This emits an information line in the log about this client's state. It
   * includes the number of choked peers, number of connected peers, number
   * of known peers, information about the torrent availability and
   * completion and current transmission rates.
   * </p>
   */
  public synchronized void info() {
    float dl = 0;
    float ul = 0;
    int numConnected = 0;
    for (SharingPeer peer : getConnectedPeers()) {
      dl += peer.getDLRate().get();
      ul += peer.getULRate().get();
      numConnected++;
    }

/*
    for (SharedTorrent torrent : this.torrents.values()) {
      logger.debug("[{}]{} {}/{} (Downloaded {} bytes) pieces ({}%) [{}/{}] with {}/{} peers at {}/{} kB/s.",
        new Object[]{
          Arrays.toString(torrent.getFilenames().toArray()),
          torrent.getClientState().name(),
          torrent.getCompletedPieces().cardinality(),
          torrent.getPieceCount(),
          torrent.getDownloaded(),
          String.format("%.2f", torrent.getCompletion()),
          torrent.getAvailablePieces().cardinality(),
          torrent.getRequestedPieces().cardinality(),
          numConnected,
          this.peers.size(),
          String.format("%.2f", dl / 1024.0),
          String.format("%.2f", ul / 1024.0),
        });
    }
*/
//    logger.debug("Downloaded bytes: {}", PeerExchange.readBytes);

  }

  /**
   * Reset peers download and upload rates.
   * <p/>
   * <p>
   * This method is called every RATE_COMPUTATION_ITERATIONS to reset the
   * download and upload rates of all peers. This contributes to making the
   * download and upload rate computations rolling averages every
   * UNCHOKING_FREQUENCY * RATE_COMPUTATION_ITERATIONS seconds (usually 20
   * seconds).
   * </p>
   */
  private synchronized void resetPeerRates() {
    for (SharingPeer peer : getConnectedPeers()) {
      peer.getDLRate().reset();
      peer.getULRate().reset();
    }
  }

  /**
   * Retrieve a peer comparator.
   * <p/>
   * <p>
   * Returns a peer comparator based on either the download rate or the
   * upload rate of each peer depending on our state. While sharing, we rely
   * on the download rate we get from each peer. When our download is
   * complete and we're only seeding, we use the upload rate instead.
   * </p>
   *
   * @return A SharingPeer comparator that can be used to sort peers based on
   * the download or upload rate we get from them.
   */
  private Comparator<SharingPeer> getPeerRateComparator() {
/*
    if (this.seed == 0) {
      return new SharingPeer.ULRateComparator();
    }
*/

    return new SharingPeer.DLRateComparator();
  }

  /**
   * Unchoke connected peers.
   * <p/>
   * <p>
   * This is one of the "clever" places of the BitTorrent client. Every
   * OPTIMISTIC_UNCHOKING_FREQUENCY seconds, we decide which peers should be
   * unchocked and authorized to grab pieces from us.
   * </p>
   * <p/>
   * <p>
   * Reciprocation (tit-for-tat) and upload capping is implemented here by
   * carefully choosing which peers we unchoke, and which peers we choke.
   * </p>
   * <p/>
   * <p>
   * The four peers with the best download rate and are interested in us get
   * unchoked. This maximizes our download rate as we'll be able to get data
   * from there four "best" peers quickly, while allowing these peers to
   * download from us and thus reciprocate their generosity.
   * </p>
   * <p/>
   * <p>
   * Peers that have a better download rate than these four downloaders but
   * are not interested get unchoked too, we want to be able to download from
   * them to get more data more quickly. If one becomes interested, it takes
   * a downloader's place as one of the four top downloaders (i.e. we choke
   * the downloader with the worst upload rate).
   * </p>
   *
   * @param optimistic Whether to perform an optimistic unchoke as well.
   */
  private synchronized void unchokePeers(boolean optimistic) {
    // Build a set of all connected peers, we don't care about peers we're
    // not connected to.
    List<SharingPeer> bound = new ArrayList<SharingPeer>(peersStorage.getSharingPeers());
    Collections.sort(bound, this.getPeerRateComparator());
    Collections.reverse(bound);
    if (bound.size() == 0) {
      logger.trace("No connected peers, skipping unchoking.");
      return;
    } else {
      logger.trace("Running unchokePeers() on {} connected peers. Client {}",
              new Object[]{bound.size(), Thread.currentThread()});
    }

    int downloaders = 0;
    Set<SharingPeer> choked = new HashSet<SharingPeer>();

    // We're interested in the top downloaders first, so use a descending
    // set.
    for (SharingPeer peer : bound) {
      if (downloaders < Client.MAX_DOWNLOADERS_UNCHOKE) {
        // Unchoke up to MAX_DOWNLOADERS_UNCHOKE interested peers
        if (peer.isInterested()) {
          downloaders++;
          peer.unchoke();
        }
        continue;
      }
      // Choke everybody else
      choked.add(peer);
    }

    // Actually choke all chosen peers (if any), except the eventual
    // optimistic unchoke.
    if (choked.size() > 0) {
      SharingPeer randomPeer = choked.toArray(
              new SharingPeer[0])[this.random.nextInt(choked.size())];

      for (SharingPeer peer : choked) {
        if (optimistic && peer == randomPeer) {
          logger.debug("Optimistic unchoke of {}.", peer);
          continue;
        }

        peer.choke();
      }
    }
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

    logger.debug("Got {} peer(s) ({}) for {} in tracker response", new Object[]{peers.size(),
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
    torrent.markCompleted(piece);
    myPieceValidatorExecutor.submit(new Runnable() {
      @Override
      public void run() {
        try {
          synchronized (piece) {
            piece.validate(torrent, piece);
            if (piece.isValid()) {
              // Send a HAVE message to all connected peers
              PeerMessage have = PeerMessage.HaveMessage.craft(piece.getIndex());
              for (SharingPeer remote : getConnectedPeers()) {
                if (remote.getTorrent().getHexInfoHash().equals(torrentHash))
                  remote.send(have);
              }

              synchronized (torrent) {
                  // Make sure the piece is marked as completed in the torrent
                  // Note: this is required because the order the
                  // PeerActivityListeners are called is not defined, and we
                  // might be called before the torrent's piece completion
                  // handler is.
                  logger.debug("Completed download of {} from {}, now has {}/{} pieces.",
                          new Object[]{
                                  piece,
                                  peer,
                                  torrent.getCompletedPieces().cardinality(),
                                  torrent.getPieceCount()
                          });

                  BitSet completed = new BitSet();
                  completed.or(torrent.getCompletedPieces());
                  completed.and(peer.getAvailablePieces());
                  if (completed.equals(peer.getAvailablePieces())) {
                    // send not interested when have no interested pieces;
                    peer.send(PeerMessage.NotInterestedMessage.craft());
                  }

                }

                if (torrent.isComplete()) {
                  //close connection with all peers for this torrent
                  logger.debug("Download of {} complete.", torrent.getDirectoryName());

                  torrent.finish();

                  AnnounceableTorrent announceableTorrent = torrentsStorage.getAnnounceableTorrent(torrentHash);

                  if (announceableTorrent == null) return;

                  try {
                    announce.getCurrentTrackerClient(announceableTorrent)
                            .announceAllInterfaces(COMPLETED, true, announceableTorrent);
                  } catch (AnnounceException e) {
                    logger.debug("unable to announce torrent {} on tracker {}", torrent, torrent.getAnnounce());
                  }

                }
            } else {
              torrent.markUncompleted(piece);
              logger.info("Downloaded piece #{} from {} was not valid ;-(. Trying another peer", piece.getIndex(), peer);
              peer.getPoorlyAvailablePieces().set(piece.getIndex());
            }
          }
        } catch (Throwable e) {
          LoggerUtils.warnWithMessageAndDebugDetails(logger, "unhandled exception in piece {} validation task", piece, e);
        }
      }
    });
  }

  @Override
  public void handlePeerDisconnected(SharingPeer peer) {
    Peer p = new Peer(peer.getIp(), peer.getPort());
    p.setPeerId(peer.getPeerId());
    p.setTorrentHash(peer.getHexInfoHash());
    logger.debug("Peer {} disconnected, [{}/{}].",
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

  @Override
  public void torrentStateChanged(ClientState newState, SharedTorrent torrent) {
    if (newState.equals(ClientState.ERROR)) {
      removeTorrent(torrent);
    }
  }

  public ConnectionManager getConnectionManager() throws IllegalStateException {
    ConnectionManager connectionManager = this.myConnectionManager;
    if (connectionManager == null) {
      throw new IllegalStateException("connection manager is null");
    }
    return connectionManager;
  }
}
