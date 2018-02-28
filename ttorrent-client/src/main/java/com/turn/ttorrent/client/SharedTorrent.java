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

import com.turn.ttorrent.bcodec.BEValue;
import com.turn.ttorrent.bcodec.InvalidBEncodingException;
import com.turn.ttorrent.client.peer.PeerActivityListener;
import com.turn.ttorrent.client.peer.SharingPeer;
import com.turn.ttorrent.client.storage.FileCollectionStorage;
import com.turn.ttorrent.client.storage.FileStorage;
import com.turn.ttorrent.client.storage.TorrentByteStorage;
import com.turn.ttorrent.client.strategy.RequestStrategy;
import com.turn.ttorrent.client.strategy.RequestStrategyImplAnyInteresting;
import com.turn.ttorrent.common.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.*;


/**
 * A torrent shared by the BitTorrent client.
 * <p/>
 * <p>
 * The {@link SharedTorrent} class extends the Torrent class with all the data
 * and logic required by the BitTorrent client implementation.
 * </p>
 * <p/>
 * <p>
 * <em>Note:</em> this implementation currently only supports single-file
 * torrents.
 * </p>
 *
 * @author mpetazzoni
 */
public class SharedTorrent extends Torrent implements PeerActivityListener {

  private static final Logger logger =
          LoggerFactory.getLogger(SharedTorrent.class);

  private final static RequestStrategy DEFAULT_REQUEST_STRATEGY = new RequestStrategyImplAnyInteresting();

  /**
   * End-game trigger ratio.
   *
   * <p>
   * Eng-game behavior (requesting already requested pieces from available
   * and ready peers to try to speed-up the end of the transfer) will only be
   * enabled when the ratio of completed pieces over total pieces in the
   * torrent is over this value.
   * </p>
   */
  private static final float ENG_GAME_COMPLETION_RATIO = 0.95f;

  private boolean stop;

  private final TorrentStatistic myTorrentStatistic;

  private long myLastAnnounceTime = -1;
  private int mySeedersCount = 0;

  private TorrentByteStorage bucket;

  private final int pieceLength;
  private final ByteBuffer piecesHashes;

  private boolean initialized;
  private Piece[] pieces;
  private SortedSet<Piece> rarest;
  private BitSet completedPieces;
  private final BitSet requestedPieces;
  private final RequestStrategy myRequestStrategy;

  private List<Peer> myDownloaders = new CopyOnWriteArrayList<Peer>();

  private volatile ClientState clientState = ClientState.WAITING;

  private boolean multiThreadHash;

  private File parentFile;
  private final boolean isLeecher;

  /**
   * Create a new shared torrent from meta-info binary data.
   *
   * @param torrent The meta-info byte data.
   * @param parent  The parent directory or location the torrent files.
   * @param seeder  Whether we're a seeder for this torrent or not (disables
   *                validation).
   * @throws IOException              If the torrent file cannot be read or decoded.
   * @throws NoSuchAlgorithmException
   */
  public SharedTorrent(byte[] torrent, File parent, boolean multiThreadHash, boolean seeder, boolean leecher, RequestStrategy requestStrategy,
                       TorrentStatisticProvider torrentStatisticProvider)
          throws IOException, NoSuchAlgorithmException {
    super(torrent, seeder);
    myTorrentStatistic = torrentStatisticProvider.getTorrentStatistic();
    this.isLeecher = leecher;
    this.parentFile = parent;
    this.myRequestStrategy = requestStrategy;

    this.multiThreadHash = multiThreadHash;

    if (parent == null || !parent.isDirectory()) {
      throw new IllegalArgumentException("Invalid parent directory!");
    }

    String parentPath = parent.getCanonicalPath();

    try {
      final Map<String, BEValue> decodedInfo = getDecodedInfo();
      this.pieceLength = decodedInfo.get("piece length").getInt();
      this.piecesHashes = ByteBuffer.wrap(decodedInfo.get("pieces")
              .getBytes());

      if (this.piecesHashes.capacity() / Torrent.PIECE_HASH_SIZE *
              (long) this.pieceLength < this.getSize()) {
        throw new IllegalArgumentException("Torrent size does not " +
                "match the number of pieces and the piece size!");
      }
    } catch (InvalidBEncodingException ibee) {
      throw new IllegalArgumentException(
              "Error reading torrent meta-info fields!");
    }

    List<FileStorage> files = new LinkedList<FileStorage>();
    long offset = 0L;
    for (TorrentFile file : this.files) {
      File actual = new File(parent, file.getRelativePathAsString());

      if (!actual.getCanonicalPath().startsWith(parentPath)) {
        throw new SecurityException("Torrent file path attempted " +
                "to break directory jail!");
      }

      actual.getParentFile().mkdirs();
      files.add(new FileStorage(actual, offset, file.size));
      offset += file.size;
    }
    this.bucket = new FileCollectionStorage(files, this.getSize());

    this.stop = false;

    this.initialized = false;
    this.pieces = new Piece[0];
    this.rarest = Collections.synchronizedSortedSet(new TreeSet<Piece>());
    this.completedPieces = new BitSet();
    this.requestedPieces = new BitSet();
  }

  public static SharedTorrent fromFile(File source, File parent, boolean multiThreadHash, boolean seeder,
                                       TorrentStatisticProvider torrentStatisticProvider)
          throws IOException, NoSuchAlgorithmException {
    return fromFile(source, parent, multiThreadHash, seeder, false,
            torrentStatisticProvider);
  }

  public static SharedTorrent fromFile(File source, File parent, boolean multiThreadHash, boolean seeder, boolean leecher,
                                       TorrentStatisticProvider torrentStatisticProvider)
          throws IOException, NoSuchAlgorithmException {
    FileInputStream fis = new FileInputStream(source);
    byte[] data = new byte[(int) source.length()];
    fis.read(data);
    fis.close();
    return new SharedTorrent(data, parent, multiThreadHash, seeder, leecher, DEFAULT_REQUEST_STRATEGY, torrentStatisticProvider);
  }

  private synchronized void openFileChannelIfNecessary() {
    logger.debug("Opening file channel for {}. Downloaders: {}", getParentFile().getAbsolutePath() + "/" + getName(), myDownloaders.size());
    try {
      if (myDownloaders.size() == 0) {
        this.bucket.open(clientState == ClientState.SEEDING || isSeeder());
      }
    } catch (IOException e) {
      logger.error("IO error when opening channel to torrent data", e);
      setClientState(ClientState.ERROR);
    }
  }

  private synchronized void closeFileChannelIfNecessary() throws IOException {
    logger.debug("Closing file  channel for {} if necessary. Downloaders: {}", getParentFile().getAbsolutePath() + "/" + getName(), myDownloaders.size());
    if (this.myDownloaders.size() == 0) {
      this.bucket.close();
    }
  }

  /**
   * Get the number of bytes uploaded for this torrent.
   */
  public long getUploaded() {
    return myTorrentStatistic.getUploadedBytes();
  }

  /**
   * Get the number of bytes downloaded for this torrent.
   * <p/>
   * <p>
   * <b>Note:</b> this could be more than the torrent's length, and should
   * not be used to determine a completion percentage.
   * </p>
   */
  public long getDownloaded() {
    return myTorrentStatistic.getDownloadedBytes();
  }

  /**
   * Get the number of bytes left to download for this torrent.
   */
  public long getLeft() {
    return myTorrentStatistic.getLeftBytes();
  }

  public int getSeedersCount() {
    return mySeedersCount;
  }

  public void setSeedersCount(int seedersCount) {
    mySeedersCount = seedersCount;
  }

  public long getLastAnnounceTime() {
    return myLastAnnounceTime;
  }

  public void setLastAnnounceTime(long lastAnnounceTime) {
    myLastAnnounceTime = lastAnnounceTime;
  }

  /**
   * Tells whether this torrent has been fully initialized yet.
   */
  public boolean isInitialized() {
    return this.initialized;
  }

  /**
   * Stop the torrent initialization as soon as possible.
   */
  public void stop() {
    this.stop = true;
  }

  public synchronized void unloadPieces() {
    // do this only for completed torrents
    if (clientState != ClientState.SEEDING || myDownloaders.size() > 0 && !bucket.isClosed()) {
      return;
    }
    initialized = false;
    this.pieces = new Piece[0];
  }

  /**
   * Build this torrent's pieces array.
   * <p/>
   * <p>
   * Hash and verify any potentially present local data and create this
   * torrent's pieces array from their respective hash provided in the
   * torrent meta-info.
   * </p>
   * <p/>
   * <p>
   * This function should be called soon after the constructor to initialize
   * the pieces array.
   * </p>
   */
  public synchronized void init() throws InterruptedException, IOException {
    setClientState(ClientState.VALIDATING);

    if (this.isInitialized()) {
      throw new IllegalStateException("Torrent was already initialized!");
    }

    try {
      openFileChannelIfNecessary();
      if (multiThreadHash) {
        hashMultiThread();
      } else {
        hashSingleThread();
      }
    } finally {
      closeFileChannelIfNecessary();
    }

    logger.debug("{}: {}/{} bytes [{}/{}].",
            new Object[]{
                    this.getName(),
                    (this.getSize() - myTorrentStatistic.getLeftBytes()),
                    this.getSize(),
                    this.completedPieces.cardinality(),
                    this.pieces.length
            });

//    Client.cleanupProcessor().registerCleanable(this);
    this.initialized = true;
  }

  private void initPieces() {
    int nPieces = (int) (Math.ceil(
            (double) this.getSize() / this.pieceLength));
    this.pieces = new Piece[nPieces];
    this.completedPieces = new BitSet(nPieces);
    this.piecesHashes.clear();
  }

  private void hashMultiThread() throws InterruptedException, IOException {
    initPieces();

    ExecutorService executor = Executors.newFixedThreadPool(
            HASHING_THREADS_COUNT);
    List<Future<Piece>> results = new LinkedList<Future<Piece>>();

    logger.debug("Analyzing local data for {} with {} threads...",
            this.getName(), HASHING_THREADS_COUNT);
    for (int idx = 0; idx < this.pieces.length; idx++) {
      byte[] hash = new byte[Torrent.PIECE_HASH_SIZE];
      this.piecesHashes.get(hash);

      // The last piece may be shorter than the torrent's global piece
      // length. Let's make sure we get the right piece length in any
      // situation.
      long off = ((long) idx) * this.pieceLength;
      long len = Math.min(
              this.bucket.size() - off,
              this.pieceLength);

      this.pieces[idx] = new Piece(this.bucket, idx, off, len, hash,
              this.isSeeder(), isLeecher);

      Callable<Piece> hasher = new Piece.CallableHasher(this.pieces[idx]);
      results.add(executor.submit(hasher));
    }

    // Request orderly executor shutdown and wait for hashing tasks to
    // complete.
    executor.shutdown();
    while (!executor.isTerminated()) {
      if (this.stop) {
        throw new InterruptedException("Torrent data analysis interrupted.");
      }

      Thread.sleep(10);
    }

    try {
      for (Future<Piece> task : results) {
        Piece piece = task.get();
        if (this.pieces[piece.getIndex()].isValid()) {
          this.completedPieces.set(piece.getIndex());
          myTorrentStatistic.addLeft(-piece.size());
        }
      }
    } catch (ExecutionException e) {
      throw new IOException("Error while hashing a torrent piece!", e);
    }
  }

  private void hashSingleThread() throws InterruptedException, IOException {
    initPieces();

    List<Piece> results = new LinkedList<Piece>();

    logger.debug("Analyzing local data for {} with {} threads...",
            this.getName(), HASHING_THREADS_COUNT);
    for (int idx = 0; idx < this.pieces.length; idx++) {
      byte[] hash = new byte[Torrent.PIECE_HASH_SIZE];
      this.piecesHashes.get(hash);

      // The last piece may be shorter than the torrent's global piece
      // length. Let's make sure we get the right piece length in any
      // situation.
      long off = ((long) idx) * this.pieceLength;
      long len = Math.min(
              this.bucket.size() - off,
              this.pieceLength);

      this.pieces[idx] = new Piece(this.bucket, idx, off, len, hash,
              this.isSeeder(), isLeecher);

      Callable<Piece> hasher = new Piece.CallableHasher(this.pieces[idx]);
      try {
        results.add(hasher.call());
      } catch (Exception e) {
        if (Thread.currentThread().isInterrupted()) break;
        logger.error("There was a problem initializing piece " + idx);
      }
    }

    for (Piece piece : results) {
      if (this.pieces[piece.getIndex()].isValid()) {
        this.completedPieces.set(piece.getIndex());
        myTorrentStatistic.addLeft(-piece.size());
      }
    }
  }

  public synchronized void close() {
    logger.trace("Closing torrent", getName());
//    Client.cleanupProcessor().unregisterCleanable(this);
    try {
      this.bucket.close();
    } catch (IOException ioe) {
      logger.error("Error closing torrent byte storage: {}",
              ioe.getMessage());
    }
  }

  public synchronized void delete() {
    logger.trace("Closing and deleting torrent data", getName());
    try {
      close();
      this.bucket.delete();
    } catch (IOException ioe) {
      logger.error("Error deleting torrent byte storage: {}",
              ioe.getMessage());
    }
  }

  /**
   * Retrieve a piece object by index.
   *
   * @param index The index of the piece in this torrent.
   */
  public Piece getPiece(int index) {
    if (this.pieces == null) {
      throw new IllegalStateException("Torrent not initialized yet.");
    }

    if (index >= this.pieces.length) {
      throw new IllegalArgumentException("Invalid piece index!");
    }

    return this.pieces[index];
  }


  public File getParentFile() {
    return parentFile;
  }

  /**
   * Return a copy of the bit field of available pieces for this torrent.
   * <p/>
   * <p>
   * Available pieces are pieces available in the swarm, and it does not
   * include our own pieces.
   * </p>
   */
  public BitSet getAvailablePieces() {
    if (!this.isInitialized()) {
      throw new IllegalStateException("Torrent not yet initialized!");
    }

    BitSet availablePieces = new BitSet(this.pieces.length);

    synchronized (this.pieces) {
      for (Piece piece : this.pieces) {
        if (piece.available()) {
          availablePieces.set(piece.getIndex());
        }
      }
    }

    return availablePieces;
  }

  /**
   * Return a copy of the completed pieces bitset.
   */
  public BitSet getCompletedPieces() {
    if (!this.isInitialized()) {
      throw new IllegalStateException("Torrent not yet initialized!");
    }

    synchronized (this.completedPieces) {
      return (BitSet) this.completedPieces.clone();
    }
  }

  /**
   * Return a copy of the requested pieces bitset.
   */
  public BitSet getRequestedPieces() {
    if (!this.isInitialized()) {
      throw new IllegalStateException("Torrent not yet initialized!");
    }

    synchronized (this.requestedPieces) {
      return (BitSet) this.requestedPieces.clone();
    }
  }

  /**
   * Tells whether this torrent has been fully downloaded, or is fully
   * available locally.
   */
  public synchronized boolean isComplete() {
    return this.pieces.length > 0 &&
            this.completedPieces.cardinality() == this.pieces.length;
  }

  /**
   * Finalize the download of this torrent.
   * <p/>
   * <p>
   * This realizes the final, pre-seeding phase actions on this torrent,
   * which usually consists in putting the torrent data in their final form
   * and at their target location.
   * </p>
   *
   * @see TorrentByteStorage#finish
   */
  public synchronized void finish() throws IOException {
    if (!this.isInitialized()) {
      throw new IllegalStateException("Torrent not yet initialized!");
    }

    if (!this.isComplete()) {
      throw new IllegalStateException("Torrent download is not complete!");
    }

    try {
      this.bucket.finish();
    } catch (IOException ex) {
      setClientState(ClientState.ERROR);
      throw ex;
    }

    setClientState(ClientState.SEEDING);
  }

  public synchronized boolean isFinished() {
    return this.isComplete() && this.bucket.isFinished();
  }

  public ClientState getClientState() {
    return this.clientState;
  }

  public void setClientState(ClientState clientState) {
    this.clientState = clientState;
  }

  /**
   * Mark a piece as completed, decrementing the piece size in bytes from our
   * left bytes to download counter.
   */
  public synchronized void markCompleted(Piece piece) {
    if (this.completedPieces.get(piece.getIndex())) {
      return;
    }

    // A completed piece means that's that much data left to download for
    // this torrent.
    myTorrentStatistic.addLeft(-piece.size());
    this.completedPieces.set(piece.getIndex());
  }

  /** PeerActivityListener handler(s). *************************************/

  /**
   * Peer choked handler.
   * <p/>
   * <p>
   * When a peer chokes, the requests made to it are canceled and we need to
   * mark the eventually piece we requested from it as available again for
   * download tentative from another peer.
   * </p>
   *
   * @param peer The peer that choked.
   */
  @Override
  public synchronized void handlePeerChoked(SharingPeer peer) {
    Set<Piece> pieces = peer.getRequestedPieces();

    if (pieces.size() > 0) {
      for (Piece piece : pieces) {
        this.requestedPieces.set(piece.getIndex(), false);
      }
    }

    logger.trace("Peer {} choked, we now have {} outstanding " +
                    "request(s): {}.",
            new Object[]{
                    peer,
                    this.requestedPieces.cardinality(),
                    this.requestedPieces
            });
  }

  /**
   * Peer ready handler.
   * <p/>
   * <p>
   * When a peer becomes ready to accept piece block requests, select a piece
   * to download and go for it.
   * </p>
   *
   * @param peer The peer that became ready.
   */
  @Override
  public synchronized void handlePeerReady(SharingPeer peer) {
    initIfNecessary(peer);
    boolean endGameMode = false;
    int requestedPiecesCount = 0;
    final BitSet interesting = peer.getAvailablePieces();
    interesting.andNot(this.completedPieces);
    interesting.andNot(this.requestedPieces);
//    interesting.andNot(peer.getPoorlyAvailablePieces());

    while (peer.getDownloadingPiecesCount() < Math.min(10, interesting.cardinality())) {
      if (!peer.isConnected()) {
        break;
      }
      logger.trace("Peer {} is ready and has {} interesting piece(s).",
              peer, interesting.cardinality());

      logger.trace("Currently requested pieces from {} : {}", peer, requestedPieces);

      // If we didn't find interesting pieces, we need to check if we're in
      // an end-game situation. If yes, we request an already requested piece
      // to try to speed up the end.
      if (interesting.cardinality() == 0) {
        interesting.or(this.requestedPieces);
        if (interesting.cardinality() == 0) {
          logger.trace("No interesting piece from {}!", peer);
          return;
        }

        if (this.completedPieces.cardinality() <
                ENG_GAME_COMPLETION_RATIO * this.pieces.length) {
          logger.trace("Not far along enough to warrant end-game mode.");
          return;
        }
        endGameMode = true;
        logger.trace("Possible end-game, we're about to request a piece " +
                "that was already requested from another peer.");
      }

      Piece chosen = myRequestStrategy.choosePiece(rarest, interesting, pieces);
      if (chosen == null) {
        logger.info("chosen piece is null");
        continue;
      }
      this.requestedPieces.set(chosen.getIndex());
      logger.trace("Requesting {} from {}, we now have {} " +
                      " outstanding request(s): {}.",
              new Object[]{chosen, peer,
                      this.requestedPieces.cardinality(),
                      this.requestedPieces
              });
      peer.downloadPiece(chosen, endGameMode);
      interesting.clear(chosen.getIndex());
      //stop requesting if in endGameMode
      if (endGameMode) return;
    }
  }

  private synchronized void initIfNecessary(SharingPeer peer) {
    if (!isInitialized()) {
      try {
        init();
      } catch (InterruptedException e) {
        logger.info("Interrupted init", e);
        peer.unbind(true);
        return;
      } catch (IOException e) {
        logger.info("IOE during init", e);
        peer.unbind(true);
        return;
      }
    }
  }

  /**
   * Piece availability handler.
   * <p/>
   * <p>
   * Handle updates in piece availability from a peer's HAVE message. When
   * this happens, we need to mark that piece as available from the peer.
   * </p>
   *
   * @param peer  The peer we got the update from.
   * @param piece The piece that became available.
   */
  @Override
  public synchronized void handlePieceAvailability(SharingPeer peer,
                                                   Piece piece) {
    // If we don't have this piece, tell the peer we're interested in
    // getting it from him.
    if (!this.completedPieces.get(piece.getIndex()) &&
            !this.requestedPieces.get(piece.getIndex())) {
      peer.interesting();
    }

    piece.seenAt(peer);
    this.rarest.add(piece);

    logger.trace("Peer {} contributes {} piece(s) [{}/{}/{}].",
            new Object[]{
                    peer,
                    peer.getAvailablePieces().cardinality(),
                    this.completedPieces.cardinality(),
                    this.getAvailablePieces().cardinality(),
                    this.pieces.length
            });

    if (!peer.isChoked() &&
            peer.isInteresting() &&
            !peer.isDownloading()) {
      this.handlePeerReady(peer);
    }
  }

  /**
   * Bit field availability handler.
   * <p/>
   * <p>
   * Handle updates in piece availability from a peer's BITFIELD message.
   * When this happens, we need to mark in all the pieces the peer has that
   * they can be reached through this peer, thus augmenting the global
   * availability of pieces.
   * </p>
   *
   * @param peer            The peer we got the update from.
   * @param availablePieces The pieces availability bit field of the peer.
   */
  @Override
  public synchronized void handleBitfieldAvailability(SharingPeer peer,
                                                      BitSet availablePieces) {
    // Determine if the peer is interesting for us or not, and notify it.
    BitSet interesting = (BitSet) availablePieces.clone();
    interesting.andNot(this.completedPieces);
    interesting.andNot(this.requestedPieces);

    if (interesting.cardinality() == 0) {
      peer.notInteresting();
    } else {
      peer.interesting();
    }

    // Record the peer has all the pieces it told us it had.
    for (int i = availablePieces.nextSetBit(0); i >= 0;
         i = availablePieces.nextSetBit(i + 1)) {
      this.pieces[i].seenAt(peer);
      this.rarest.add(this.pieces[i]);
    }

    logger.trace("Peer {} contributes {} piece(s) [{}/{}/{}].",
            new Object[]{
                    peer,
                    availablePieces.cardinality(),
                    this.completedPieces.cardinality(),
                    this.getAvailablePieces().cardinality(),
                    this.pieces.length
            });
  }

  public int getDownloadersCount() {
    return myDownloaders.size();
  }

  @Override
  public void afterPeerRemoved(SharingPeer peer) {

  }

  /**
   * Piece upload completion handler.
   * <p/>
   * <p>
   * When a piece has been sent to a peer, we just record that we sent that
   * many bytes. If the piece is valid on the peer's side, it will send us a
   * HAVE message and we'll record that the piece is available on the peer at
   * that moment (see <code>handlePieceAvailability()</code>).
   * </p>
   *
   * @param peer  The peer we got this piece from.
   * @param piece The piece in question.
   */
  @Override
  public synchronized void handlePieceSent(SharingPeer peer, Piece piece) {
    logger.trace("Completed upload of {} to {}.", piece, peer);
    myTorrentStatistic.addUploaded(piece.size());
  }

  /**
   * Piece download completion handler.
   * <p/>
   * <p>
   * If the complete piece downloaded is valid, we can record in the torrent
   * completedPieces bit field that we know have this piece.
   * </p>
   *
   * @param peer  The peer we got this piece from.
   * @param piece The piece in question.
   */
  @Override
  public synchronized void handlePieceCompleted(SharingPeer peer,
                                                Piece piece) throws IOException {
    // Regardless of validity, record the number of bytes downloaded and
    // mark the piece as not requested anymore
    myTorrentStatistic.addDownloaded(piece.size());
    this.requestedPieces.set(piece.getIndex(), false);

    logger.trace("We now have {} piece(s) and {} outstanding request(s): {}",
            new Object[]{
                    this.completedPieces.cardinality(),
                    this.requestedPieces.cardinality(),
                    this.requestedPieces
            });
  }

  /**
   * Peer disconnection handler.
   * <p/>
   * <p>
   * When a peer disconnects, we need to mark in all of the pieces it had
   * available that they can't be reached through this peer anymore.
   * </p>
   *
   * @param peer The peer we got this piece from.
   */
  @Override
  public synchronized void handlePeerDisconnected(SharingPeer peer) {
    BitSet availablePieces = peer.getAvailablePieces();

    for (int i = availablePieces.nextSetBit(0); i >= 0;
         i = availablePieces.nextSetBit(i + 1)) {
      this.pieces[i].noLongerAt(peer);
      this.rarest.add(this.pieces[i]);
    }

    Set<Piece> requested = peer.getRequestedPieces();
    if (requested != null) {
      for (Piece piece : requested) {
        this.requestedPieces.set(piece.getIndex(), false);
      }
    }

    myDownloaders.remove(peer);

    try {
      closeFileChannelIfNecessary();
    } catch (IOException e) {
      logger.info("I/O error on attempt to close file storage: " + e.toString());
    }

    logger.debug("Peer {} went away with {} piece(s) [{}/{}/{}].",
            new Object[]{
                    peer,
                    availablePieces.cardinality(),
                    this.completedPieces.cardinality(),
                    this.getAvailablePieces().cardinality(),
                    this.pieces.length
            });
    logger.trace("We now have {} piece(s) and {} outstanding request(s): {}",
            new Object[]{
                    this.completedPieces.cardinality(),
                    this.requestedPieces.cardinality(),
                    this.requestedPieces
            });
  }

  @Override
  public synchronized void handleIOException(SharingPeer peer,
                                             IOException ioe) { /* Do nothing */ }

  @Override
  public synchronized void handleNewPeerConnected(SharingPeer peer) {
    initIfNecessary(peer);
    openFileChannelIfNecessary();
    if (clientState != ClientState.ERROR) {
      myDownloaders.add(peer);
    }
  }

  @Override
  public String toString() {
    return "SharedTorrent{" +
            Arrays.toString(getFilenames().toArray()) +
            "}";
  }
}
