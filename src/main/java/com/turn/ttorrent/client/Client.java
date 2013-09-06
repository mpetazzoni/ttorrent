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

import com.turn.ttorrent.client.announce.Announce;
import com.turn.ttorrent.client.announce.AnnounceException;
import com.turn.ttorrent.client.announce.AnnounceResponseListener;
import com.turn.ttorrent.client.announce.TrackerClient;
import com.turn.ttorrent.client.peer.PeerActivityListener;
import com.turn.ttorrent.client.peer.PeerExchange;
import com.turn.ttorrent.client.peer.SharingPeer;
import com.turn.ttorrent.client.peer.SharingPeerInfo;
import com.turn.ttorrent.common.Peer;
import com.turn.ttorrent.common.Torrent;
import com.turn.ttorrent.common.TorrentHash;
import com.turn.ttorrent.common.protocol.PeerMessage;
import com.turn.ttorrent.common.protocol.TrackerMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

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
 * Then, instantiate your Client object with this SharedTorrent and call one of
 * {@link #download} to simply download the torrent, or {@link #share} to
 * download and continue seeding for the given amount of time after the
 * download completes.
 * </p>
 *
 * @author mpetazzoni
 */
public class Client implements Runnable,
  AnnounceResponseListener, CommunicationListener, PeerActivityListener {

  protected static final Logger logger =
    LoggerFactory.getLogger(Client.class);

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

  private static final String BITTORRENT_ID_PREFIX = "-TO0042-";

  private Peer self;

  private Thread thread;
  private boolean stop;
  private long seed;

  private ConnectionHandler service;
  private final Collection<SharingPeer> peers;

  private Announce announce;
  private final ConcurrentMap<String, SharedTorrent> torrents;

  private Random random;

  /**
   * Initialize the BitTorrent client.
   *
   * @param address The address to bind to.
   */
  public Client(InetAddress address) throws IOException {
    this (address, null, 60);
  }

  public Client(InetAddress address, URI defaultTrackerURI, final int announceInterval) throws IOException {
    this.torrents = new ConcurrentHashMap<String, SharedTorrent>();

    String id = Client.BITTORRENT_ID_PREFIX + UUID.randomUUID()
      .toString().split("-")[4];

    // Initialize the incoming connection handler and register ourselves to
    // it.
    this.service = new ConnectionHandler(this.torrents, id, address);
    this.service.register(this);

    this.self = new Peer(
      this.service.getSocketAddress()
        .getAddress().getHostAddress(),
      (short) this.service.getSocketAddress().getPort(),
      ByteBuffer.wrap(id.getBytes(Torrent.BYTE_ENCODING)));

    // Initialize the announce request thread, and register ourselves to it
    // as well.

    this.announce = new Announce(this.self, announceInterval);
    announce.start(defaultTrackerURI, this);

    logger.info("BitTorrent client [{}] started and " +
      "listening at {}:{}...",
      new Object[]{
        this.self.getShortHexPeerId(),
        this.self.getIp(),
        this.self.getPort()
      });

    this.peers = new CopyOnWriteArrayList<SharingPeer>();
    this.random = new Random(System.currentTimeMillis());
  }

  public void addTorrent(SharedTorrent torrent) throws IOException, InterruptedException {
    torrent.init();
    if (!torrent.isInitialized()) {
      torrent.close();
      return;
    }

    this.torrents.put(torrent.getHexInfoHash(), torrent);

    // Initial completion test
    if (torrent.isComplete()) {
      torrent.setClientState(ClientState.SEEDING);
    } else {
      torrent.setClientState(ClientState.SHARING);
    }

    this.announce.addTorrent(torrent, this);
  }

  public void removeTorrent(TorrentHash torrentHash) {
    this.announce.removeTorrent(torrentHash);

    SharedTorrent torrent = this.torrents.remove(torrentHash.getHexInfoHash());
    if (torrent != null) {
      torrent.setClientState(ClientState.DONE);
      torrent.close();
    }
  }

  public InetAddress getAddress(){
    return service.getSocketAddress().getAddress();
  }
  /**
   * Get this client's peer specification.
   */
  public Peer getPeerSpec() {
    return this.self;
  }

  public void setAnnounceInterval(final int announceInterval){
    announce.setAnnounceInterval(announceInterval);
  }

  /**
   * Return the torrent this client is exchanging on.
   */
  public Collection<SharedTorrent> getTorrents() {
    return this.torrents.values();
  }

  public Map<String, SharedTorrent> getTorrentsMap(){
    return torrents;
  }

  public SharedTorrent getTorrentByFilePath(File file){
    String path = file.getAbsolutePath();
    for (SharedTorrent torrent : torrents.values()) {
      File parentFile = torrent.getParentFile();
      final List<String> filenames = torrent.getFilenames();
      for (String filename : filenames) {
        File seededFile = new File(parentFile, filename);
        if (seededFile.getAbsolutePath().equals(path)){
          return torrent;
        }
      }
    }
    return null;
  }

  public boolean tryTracker(Torrent torrent){
    try {
      TrackerClient trackerClient = announce.getCurrentTrackerClient(torrent);
      if (trackerClient == null) {
        final List<List<URI>> announceList = torrent.getAnnounceList();
        final URI firstTracker = announceList.get(0).get(0);
        trackerClient = Announce.createTrackerClient(self, firstTracker);
      }
      trackerClient.announce(TrackerMessage.AnnounceRequestMessage.RequestEvent.NONE, true, torrent);
      return trackerClient.getTrackerURI() != null;
    } catch (Exception e) {
      return false;
    }

  }

  public URI getDefaultTrackerURI(){
    return announce.getDefaultTrackerURI();
  }

  /**
   * Returns the set of known peers.
   */
  public Set<SharingPeer> getPeers() {
    return new HashSet<SharingPeer>(this.peers);
  }

  /**
   * Download the torrent without seeding after completion.
   */
  public void download() {
    this.share(0);
  }

  /**
   * Download and share this client's torrent until interrupted.
   */
  public void share() {
    this.share(-1);
  }

  /**
   * Download and share this client's torrents.
   *
   * @param seed Seed time in seconds after the download is complete. Pass
   *             <code>0</code> to immediately stop after downloading.
   */
  public synchronized void share(int seed) {
    this.seed = seed;
    this.stop = false;

    if (this.thread == null || !this.thread.isAlive()) {
      this.thread = new Thread(this);
      this.thread.setName("bt-client(" +
        this.self.getShortHexPeerId() + ")");
      this.thread.start();
    }
  }

  /**
   * Immediately but gracefully stop this client.
   */
  public void stop() {
    this.stop(true);
  }

  /**
   * Immediately but gracefully stop this client.
   *
   * @param wait Whether to wait for the client execution thread to complete
   *             or not. This allows for the client's state to be settled down in one of
   *             the <tt>DONE</tt> or <tt>ERROR</tt> states when this method returns.
   */
  public void stop(boolean wait) {
    this.stop = true;

    if (this.thread != null && this.thread.isAlive()) {
      this.thread.interrupt();
      if (wait) {
          this.waitForCompletion();
      }
    }

      this.thread = null;
  }

    /**
     * Wait for downloading (and seeding, if requested) to complete.
     */
    public void waitForCompletion() {
        if (this.thread != null && this.thread.isAlive()) {
        try {
          this.thread.join();
        } catch (InterruptedException ie) {
            logger.error(ie.getMessage(), ie);
        }
      }
  }

  /**
   * Tells whether we are a seed for the torrent we're sharing.
   */
  public boolean isSeed(String hexInfoHash) {
    SharedTorrent t = this.torrents.get(hexInfoHash);
    return t != null && t.isComplete();
  }

  public void downloadUninterruptibly(SharedTorrent torrent, long downloadTimeoutSeconds) throws IOException, InterruptedException {
    addTorrent(torrent);
    // we must ensure that at every moment we are downloading a piece of that torrent
    long startTime = System.currentTimeMillis();
    while (!torrent.isComplete() &&
        (torrent.getSeedersCount() > 0 || torrent.getLastAnnounceTime() < 0) &&
        (System.currentTimeMillis() - startTime <= downloadTimeoutSeconds*1000)){
      Thread.sleep(100);
    }
    if (!torrent.isComplete()) {
      removeTorrent(torrent);
      throw new IOException("Unable to download torrent completely - no full seeder or timed out");
    }
  }

  /**
   * Main client loop.
   * <p/>
   * <p>
   * The main client download loop is very simple: it starts the announce
   * request thread, the incoming connection handler service, and loops
   * unchoking peers every UNCHOKING_FREQUENCY seconds until told to stop.
   * Every OPTIMISTIC_UNCHOKE_ITERATIONS, an optimistic unchoke will be
   * attempted to try out other peers.
   * </p>
   * <p/>
   * <p>
   * Once done, it stops the announce and connection services, and returns.
   * </p>
   */
  @Override
  public void run() {
    // Detect early stop
    if (this.stop) {
      logger.info("Early stop detected. Stopping...");
      this.finish();
      return;
    }

    this.service.start();

    int optimisticIterations = 0;
    int rateComputationIterations = 0;

    while (!this.stop) {
      optimisticIterations =
        (optimisticIterations == 0 ?
          Client.OPTIMISTIC_UNCHOKE_ITERATIONS :
          optimisticIterations - 1);

      rateComputationIterations =
        (rateComputationIterations == 0 ?
          Client.RATE_COMPUTATION_ITERATIONS :
          rateComputationIterations - 1);

      try {
        this.unchokePeers(optimisticIterations == 0);
        this.info();
        if (rateComputationIterations == 0) {
          this.resetPeerRates();
        }
      } catch (Exception e) {
        logger.error("An exception occurred during the BitTorrent " +
          "client main loop execution!", e);
      }

      try {
        Thread.sleep(Client.UNCHOKING_FREQUENCY * 1000);
      } catch (InterruptedException ie) {
        logger.trace("BitTorrent main loop interrupted.");
      }
    }

    logger.debug("Stopping BitTorrent client connection service " +
      "and announce threads...");
    this.service.stop();
    try {
      this.service.close();
    } catch (IOException ioe) {
      logger.warn("Error while releasing bound channel: {}!",
              ioe.getMessage(), ioe);
    }


    // Close all peer connections
    logger.debug("Closing all remaining peer connections...");
    for (SharingPeer peer : this.peers) {
      peer.unbind(true);
    }

    this.finish();
  }

  public boolean isRunning(){
    return this.thread != null && this.thread.isAlive();
  }

  /**
   * Close torrent and set final client state before signing off.
   */
  private void finish() {

    this.announce.stop();

    for (SharedTorrent torrent : this.torrents.values()) {
      torrent.close();
      if (torrent.isFinished()) {
        torrent.setClientState(ClientState.DONE);
      } else {
        torrent.setClientState(ClientState.ERROR);
      }
    }

    logger.info("BitTorrent client signing off.");
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
    logger.debug("Downloaded bytes: {}", PeerExchange.readBytes);

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
   * Retrieve a SharingPeer object from the given peer specification.
   * <p/>
   * <p>
   * This function tries to retrieve an existing peer object based on the
   * provided peer specification or otherwise instantiates a new one and adds
   * it to our peer repository.
   * </p>
   *
   * @param search      The {@link com.turn.ttorrent.common.Peer} specification.
   * @param hexInfoHash
   */
  private SharingPeer getOrCreatePeer(Peer search, String hexInfoHash) {
//    SharingPeer peer;

    synchronized (this.peers) {
      logger.trace("Searching for {}...", search);

      List<SharingPeer> found = new ArrayList<SharingPeer>();
      for (SharingPeer p : this.peers) {
        if ((search.getHexPeerId() != null && search.getHexPeerId().equals(p.getHexPeerId())) ||
          (search.getHostIdentifier() != null && search.getHostIdentifier().equals(p.getHostIdentifier()))) {
          found.add(p);
        }
      }


      for (SharingPeer f : found) {
        if (search.hasPeerId()) {
          f.setPeerId(search.getPeerId());
        }
      }

      for (SharingPeer f : found) {
        if (f.getTorrentHexInfoHash().equals(hexInfoHash)) {
          logger.trace("Found peer: {}.", f);
          return f;
        }
      }

      SharedTorrent torrent = this.torrents.get(hexInfoHash);

      SharingPeer peer = new SharingPeer(search.getIp(), search.getPort(),
        search.getPeerId(), torrent);
      logger.trace("Created new peer: {}.", peer);

      return peer;
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
   *         the download or upload rate we get from them.
   */
  private Comparator<SharingPeer> getPeerRateComparator() {
    if (this.seed == 0) {
      return new SharingPeer.ULRateComparator();
    }

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
    List<SharingPeer> bound = new ArrayList<SharingPeer>(getConnectedPeers());
    Collections.sort(bound, this.getPeerRateComparator());
    Collections.reverse(bound);

    if (bound.size() == 0) {
      logger.trace("No connected peers, skipping unchoking.");
      return;
    } else {
      logger.trace("Running unchokePeers() on {} connected peers.",
        bound.size());
    }

    int downloaders = 0;
    Set<SharingPeer> choked = new HashSet<SharingPeer>();

    // We're interested in the top downloaders first, so use a descending
    // set.
    for (SharingPeer peer : bound) {
      if (downloaders < Client.MAX_DOWNLOADERS_UNCHOKE) {
        // Unchoke up to MAX_DOWNLOADERS_UNCHOKE interested peers
        if (peer.isChoking()) {
          if (peer.isInterested()) {
            downloaders++;
          }

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
    for (SharingPeer peer : this.peers) {
      if (peer.isConnected()) {
        result.add(peer);
      }
    }
    return result;
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
//    this.announce.setInterval(interval);
    final SharedTorrent sharedTorrent = this.torrents.get(hexInfoHash);
    if (sharedTorrent != null){
      sharedTorrent.setSeedersCount(complete);
      sharedTorrent.setLastAnnounceTime(System.currentTimeMillis());
    }
  }

  /**
   * Handle the discovery of new peers.
   *
   * @param peers The list of peers discovered (from the announce response or
   *              any other means like DHT/PEX, etc.).
   */
  @Override
  public void handleDiscoveredPeers(List<Peer> peers, String hexInfoHash) {
    logger.debug("Got {} peer(s) for {} in tracker response", peers.size(), hexInfoHash);

    if (!this.service.isAlive()) {
      logger.info("Connection handler service is not available.");
      return;
    }

    Set<SharingPeer> foundPeers = new HashSet<SharingPeer>();
    Set<SharingPeer> addedPeers = new HashSet<SharingPeer>();
    for (Peer peer : peers) {
      SharingPeer match = this.getOrCreatePeer(peer, hexInfoHash);
      foundPeers.add(match);

      // Attempt to connect to the peer if and only if:
      //   - We're not already connected to it;
      //   - We're not a seeder (we leave the responsibility
      //	   of connecting to peers that need to download
      //     something), or we are a seeder but we're still
      //     willing to initiate some out bound connections.
      if (match.isConnected() || this.isSeed(hexInfoHash) || match.getTorrent().isFinished()) {
        continue;
      }

      addedPeers.add(match);
    }

    List<SharingPeer> toRemove = new ArrayList<SharingPeer>();
    for (SharingPeer peer : this.peers) {
      if (peer.getTorrentHexInfoHash().equals(hexInfoHash) && foundPeers.contains(peer) && !peer.isConnected()) {
        toRemove.add(peer);
      }
    }
    this.peers.removeAll(toRemove);
    for (SharingPeer peer : toRemove) {
      peer.unbind(true);
    }
    peers.addAll(addedPeers);
    for (SharingPeer addedPeer : addedPeers) {
      this.service.connect(addedPeer);
    }

  }

  /** CommunicationListener handler(s). ********************************/

  /**
   * Handle a new peer connection.
   * <p/>
   * <p>
   * This handler is called once the connection has been successfully
   * established and the handshake exchange made. This generally simply means
   * binding the peer to the socket, which will put in place the communication
   * thread and logic with this peer.
   * </p>
   *
   * @param channel The connected socket to the remote peer. Note that if the peer
   *               somehow rejected our handshake reply, this socket might very soon get
   *               closed, but this is handled down the road.
   * @param peerId The byte-encoded peerId extracted from the peer's
   *               handshake, after validation.
   * @see com.turn.ttorrent.client.peer.SharingPeer
   */
  @Override
	public void handleNewPeerConnection(SocketChannel channel, byte[] peerId, String hexInfoHash) {
    Peer search = new Peer(
			channel.socket().getInetAddress().getHostAddress(),
			channel.socket().getPort(),
      (peerId != null
        ? ByteBuffer.wrap(peerId)
        : null));

    logger.debug("Handling new peer connection with {}...", search);
    SharingPeer peer = this.getOrCreatePeer(search, hexInfoHash);

    try {
      synchronized (peer) {
        if (peer.isConnected()) {
          logger.debug("Already connected with {}, ignoring.", peer);
          return;
        }

        peer.register(this);
        peer.bind(channel);
        peers.add(peer);
      }

      peer.register(peer.getTorrent());
      logger.debug("New peer connection with {} [{}/{}].",
        new Object[]{
          peer,
          getConnectedPeers().size(),
          this.peers.size()
        });
    } catch (Exception e) {
      logger.warn("Could not handle new peer connection " +
        "with {}: {}", peer, e.getMessage());
    }
  }

  /**
   * Handle a failed peer connection.
   * <p/>
   * <p>
   * If an outbound connection failed (could not connect, invalid handshake,
   * etc.), remove the peer from our known peers.
   * </p>
   *
   * @param peer  The peer we were trying to connect with.
   * @param cause The exception encountered when connecting with the peer.
   */
  @Override
  public void handleFailedConnection(Peer peer, Throwable cause) {
    logger.debug("Could not connect to {}: {}.", peer, cause.getMessage());
    for (SharingPeer sharingPeer : peers) {
      if (peer.getIp().equals(sharingPeer.getIp()) && peer.getPort()== sharingPeer.getPort()){
        peers.remove(sharingPeer);
        break; // this is safe to do, because we go out from iterator
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
  public void handlePieceCompleted(SharingPeer peer, Piece piece)
    throws IOException {
    final SharedTorrent torrent = peer.getTorrent();
    synchronized (torrent) {
      if (piece.isValid()) {
        // Make sure the piece is marked as completed in the torrent
        // Note: this is required because the order the
        // PeerActivityListeners are called is not defined, and we
        // might be called before the torrent's piece completion
        // handler is.
        torrent.markCompleted(piece);
        logger.debug("Completed download of {}, now has {}/{} pieces.",
          new Object[]{
            piece,
            torrent.getCompletedPieces().cardinality(),
            torrent.getPieceCount()
          });

        // Send a HAVE message to all connected peers
        PeerMessage have = PeerMessage.HaveMessage.craft(piece.getIndex());
        for (SharingPeer remote : getConnectedPeers()) {
          remote.send(have);
        }
      } else {
        logger.debug("Downloaded piece#{} from {} was not valid ;-(. Trying another peer", piece.getIndex(), peer);
        peer.getPoorlyAvailablePieces().set(piece.getIndex());
      }

      if (torrent.isComplete()) {
          logger.info("Download of {} complete.", torrent.getName());

          // Cancel all remaining outstanding requests
          //TODO: rework this in case of multi-torrent client
/*
          for (SharingPeer remote : this.connected.values()) {
              if (remote.isDownloading()) {
                  int requests = remote.cancelPendingRequests().size();
                  logger.info("Cancelled {} remaining pending requests on {}.",
                          requests, remote);
              }
          }
*/
        torrent.finish();

        try {
          this.announce.getCurrentTrackerClient(torrent)
            .announce(TrackerMessage
              .AnnounceRequestMessage
              .RequestEvent.COMPLETED, true, torrent);
        } catch (AnnounceException ae) {
          logger.debug("Error announcing completion event to tracker: {}", ae.getMessage());
        }

        torrent.setClientState(ClientState.SEEDING);
        if (seed == 0) {
          peer.unbind(false);
          this.announce.removeTorrent(torrent);
        }
      }
    }
  }

  @Override
  public void handlePeerDisconnected(SharingPeer peer) {
    peer.reset();
    this.peers.remove(peer);
    logger.debug("Peer {} disconnected, [{}/{}].",
      new Object[]{
        peer,
        getConnectedPeers().size(),
        this.peers.size()
      });
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


  /** Post download seeding. ************************************************/

  /**
   * Timer task to stop seeding.
   * <p/>
   * <p>
   * This TimerTask will be called by a timer set after the download is
   * complete to stop seeding from this client after a certain amount of
   * requested seed time (might be 0 for immediate termination).
   * </p>
   * <p/>
   * <p>
   * This task simply contains a reference to this client instance and calls
   * its <code>stop()</code> method to interrupt the client's main loop.
   * </p>
   *
   * @author mpetazzoni
   */
  private static class ClientShutdown extends TimerTask {

    private final Client client;
    private final Timer timer;

    ClientShutdown(Client client, Timer timer) {
      this.client = client;
      this.timer = timer;
    }

    @Override
    public void run() {
      this.client.stop();
      if (this.timer != null) {
        this.timer.cancel();
      }
    }
  }

  @Override
  public void handleNewConnection(SocketChannel s, String hexInfoHash) { /* Do nothing */}

  @Override
  public void handleReturnedHandshake(SocketChannel s, List<ByteBuffer> data) { /* Do nothing */ }

  @Override
  public void handleNewData(SocketChannel s, List<ByteBuffer> data) { /* Do nothing */ }
}
