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

import com.turn.ttorrent.Constants;
import com.turn.ttorrent.client.peer.PeerActivityListener;
import com.turn.ttorrent.client.peer.SharingPeer;
import com.turn.ttorrent.client.storage.PieceStorage;
import com.turn.ttorrent.client.storage.TorrentByteStorage;
import com.turn.ttorrent.client.strategy.*;
import com.turn.ttorrent.common.Optional;
import com.turn.ttorrent.common.*;
import org.apache.commons.io.FileUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;


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
public class SharedTorrent implements PeerActivityListener, TorrentMetadata, TorrentInfo {

  private static final Logger logger =
          TorrentLoggerFactory.getLogger();

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
  private static final int END_GAME_STATIC_PIECES_COUNT = 20;
  private static final long END_GAME_INVOCATION_PERIOD_MS = 2000;

  private final TorrentStatistic myTorrentStatistic;

  private long myLastAnnounceTime = -1;
  private int mySeedersCount = 0;

  private final PieceStorage pieceStorage;
  private boolean isFileChannelOpen = false;
  private final Map<Integer, Future<?>> myValidationFutures;
  private final TorrentMetadata myTorrentMetadata;
  private final long myTorrentTotalSize;

  private final int pieceLength;
  private final ByteBuffer piecesHashes;

  private boolean initialized;
  private Piece[] pieces;
  private final BitSet completedPieces;
  private final BitSet requestedPieces;
  private final RequestStrategy myRequestStrategy;
  private final EventDispatcher eventDispatcher;

  private final List<SharingPeer> myDownloaders = new CopyOnWriteArrayList<SharingPeer>();
  private final EndGameStrategy endGameStrategy = new EndGameStrategyImpl(2);
  private volatile long endGameEnabledOn = -1;

  private volatile ClientState clientState = ClientState.WAITING;
  private static final int MAX_VALIDATION_TASK_COUNT = 200;
  private static final int MAX_REQUESTED_PIECES_PER_TORRENT = 100;

  /**
   * Create a new shared torrent from meta-info
   *
   * @param torrentMetadata The meta-info
   * @param eventDispatcher
   */
  public SharedTorrent(TorrentMetadata torrentMetadata, PieceStorage pieceStorage, RequestStrategy requestStrategy,
                       TorrentStatistic torrentStatistic, EventDispatcher eventDispatcher) {
    myTorrentMetadata = torrentMetadata;
    this.pieceStorage = pieceStorage;
    this.eventDispatcher = eventDispatcher;
    myTorrentStatistic = torrentStatistic;
    myValidationFutures = new HashMap<Integer, Future<?>>();
    long totalSize = 0;
    for (TorrentFile torrentFile : myTorrentMetadata.getFiles()) {
      totalSize += torrentFile.size;
    }
    myTorrentTotalSize = totalSize;
    this.myRequestStrategy = requestStrategy;

    this.pieceLength = myTorrentMetadata.getPieceLength();
    this.piecesHashes = ByteBuffer.wrap(myTorrentMetadata.getPiecesHashes());

    if (this.piecesHashes.capacity() / Constants.PIECE_HASH_SIZE *
            (long) this.pieceLength < myTorrentTotalSize) {
      throw new IllegalArgumentException("Torrent size does not " +
              "match the number of pieces and the piece size!");
    }

    this.initialized = false;
    this.pieces = new Piece[0];
    this.completedPieces = new BitSet(torrentMetadata.getPiecesCount());
    this.requestedPieces = new BitSet();
  }

  public static SharedTorrent fromFile(File source, PieceStorage pieceStorage, TorrentStatistic torrentStatistic)
          throws IOException {
    byte[] data = FileUtils.readFileToByteArray(source);
    TorrentMetadata torrentMetadata = new TorrentParser().parse(data);
    return new SharedTorrent(torrentMetadata, pieceStorage, DEFAULT_REQUEST_STRATEGY, torrentStatistic, new EventDispatcher(new ArrayList<TorrentListener>()));
  }

  private synchronized void closeFileChannelIfNecessary() throws IOException {
    if (isFileChannelOpen && myDownloaders.size() == 0) {
      logger.debug("Closing file  channel for {} if necessary. Downloaders: {}", getHexInfoHash(), myDownloaders.size());
      this.pieceStorage.close();
      isFileChannelOpen = false;
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

    hashSingleThread();

    this.initialized = true;
  }

  private void initPieces() {
    int nPieces = (int) (Math.ceil(
            (double) myTorrentTotalSize / this.pieceLength));
    this.pieces = new Piece[nPieces];
    this.piecesHashes.clear();
  }

  private void hashSingleThread() {
    initPieces();

    logger.debug("Analyzing local data for {} with {} threads...",
            myTorrentMetadata.getDirectoryName(), TorrentCreator.HASHING_THREADS_COUNT);
    for (int idx = 0; idx < this.pieces.length; idx++) {
      byte[] hash = new byte[Constants.PIECE_HASH_SIZE];
      this.piecesHashes.get(hash);

      // The last piece may be shorter than the torrent's global piece
      // length. Let's make sure we get the right piece length in any
      // situation.
      long off = ((long) idx) * this.pieceLength;
      long len = Math.min(
              myTorrentTotalSize - off,
              this.pieceLength);

      Piece piece = new Piece(this.pieceStorage, idx, len, hash
      );
      this.pieces[idx] = piece;
      piece.setValid(pieceStorage.getAvailablePieces().get(idx));
    }

    for (Piece piece : pieces) {
      if (piece.isValid()) {
        this.completedPieces.set(piece.getIndex());
        myTorrentStatistic.addLeft(-piece.size());
      }
    }
  }

  public synchronized void close() {
    logger.trace("Closing torrent", myTorrentMetadata.getDirectoryName());
    try {
      this.pieceStorage.close();
      isFileChannelOpen = false;
    } catch (IOException ioe) {
      logger.error("Error closing torrent byte storage: {}",
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

    return pieceStorage.getAvailablePieces();
  }

  /**
   * Tells whether this torrent has been fully downloaded, or is fully
   * available locally.
   */
  public synchronized boolean isComplete() {
    return this.pieces.length > 0
            && pieceStorage.getAvailablePieces().cardinality() == myTorrentMetadata.getPiecesCount();
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
  public synchronized void finish() {
    if (!this.isInitialized()) {
      throw new IllegalStateException("Torrent not yet initialized!");
    }

    if (!this.isComplete()) {
      throw new IllegalStateException("Torrent download is not complete!");
    }

    eventDispatcher.notifyDownloadComplete();
    setClientState(ClientState.SEEDING);
  }

  public boolean isFinished() {
    return pieceStorage.getAvailablePieces().cardinality() == myTorrentMetadata.getPiecesCount();
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

  public synchronized void markUncompleted(Piece piece) {
    if (!this.completedPieces.get(piece.getIndex())) {
      return;
    }

    removeValidationFuture(piece);
    myTorrentStatistic.addLeft(piece.size());
    this.completedPieces.clear(piece.getIndex());
  }

  public synchronized void removeValidationFuture(Piece piece) {
    myValidationFutures.remove(piece.getIndex());
  }

  public void notifyPieceDownloaded(Piece piece, SharingPeer peer) {
    eventDispatcher.notifyPieceDownloaded(piece, peer);
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
  public void handlePeerReady(SharingPeer peer) {
    initIfNecessary(peer);

    RequestsCollection requestsCollection = getRequestsCollection(peer);
    requestsCollection.sendAllRequests();
  }

  @NotNull
  private synchronized RequestsCollection getRequestsCollection(final SharingPeer peer) {
    if (myValidationFutures.size() > MAX_VALIDATION_TASK_COUNT) return RequestsCollection.Empty.INSTANCE;

    if (this.requestedPieces.cardinality() > MAX_REQUESTED_PIECES_PER_TORRENT) return RequestsCollection.Empty.INSTANCE;

    int completedAndValidated = pieceStorage.getAvailablePieces().cardinality();

    boolean turnOnEndGame = completedAndValidated > getPiecesCount() * ENG_GAME_COMPLETION_RATIO ||
            completedAndValidated > getPiecesCount() - END_GAME_STATIC_PIECES_COUNT;
    if (turnOnEndGame) {
      long now = System.currentTimeMillis();
      if (now - END_GAME_INVOCATION_PERIOD_MS > endGameEnabledOn) {
        endGameEnabledOn = now;
        return endGameStrategy.collectRequests(pieces, myDownloaders);
      }
      return RequestsCollection.Empty.INSTANCE;
    }

    final BitSet interesting = peer.getAvailablePieces();
    interesting.andNot(this.completedPieces);
    interesting.andNot(this.requestedPieces);

    int maxRequestingPieces = Math.min(10, interesting.cardinality());
    int currentlyDownloading = peer.getDownloadingPiecesCount();
    Map<Piece, List<SharingPeer>> toRequest = new HashMap<Piece, List<SharingPeer>>();
    while (currentlyDownloading < maxRequestingPieces) {
      if (!peer.isConnected()) {
        break;
      }

      if (interesting.cardinality() == 0) {
        return RequestsCollection.Empty.INSTANCE;
      }

      Piece chosen = myRequestStrategy.choosePiece(interesting, pieces);
      if (chosen == null) {
        logger.info("chosen piece is null");
        break;
      }
      this.requestedPieces.set(chosen.getIndex());
      currentlyDownloading++;
      toRequest.put(chosen, Collections.singletonList(peer));
      interesting.clear(chosen.getIndex());
    }

    return new RequestsCollectionImpl(toRequest);
  }

  public synchronized void initIfNecessary(SharingPeer peer) {
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
  public void handlePieceAvailability(SharingPeer peer, Piece piece) {
    boolean isPeerInteresting = !this.completedPieces.get(piece.getIndex()) &&
            !this.requestedPieces.get(piece.getIndex());
    if (isPeerInteresting) {
      peer.interesting();
    }

    piece.seenAt(peer);

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
  public void handleBitfieldAvailability(SharingPeer peer,
                                         BitSet availablePieces) {
    // Determine if the peer is interesting for us or not, and notify it.
    BitSet interesting = (BitSet) availablePieces.clone();
    synchronized (this) {
      interesting.andNot(this.completedPieces);
      interesting.andNot(this.requestedPieces);
    }
    // Record the peer has all the pieces it told us it had.
    for (int i = availablePieces.nextSetBit(0); i >= 0;
         i = availablePieces.nextSetBit(i + 1)) {
      this.pieces[i].seenAt(peer);
    }

    if (interesting.cardinality() == 0) {
      peer.notInteresting();
    } else {
      peer.interesting();
    }

    logger.debug("Peer {} contributes {} piece(s), total pieces count: {}.",
            new Object[]{
                    peer,
                    availablePieces.cardinality(),
                    myTorrentMetadata.getPiecesCount()
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
  public void handlePieceSent(SharingPeer peer, Piece piece) {
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
  public void handlePieceCompleted(SharingPeer peer,
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

    logger.debug("Peer {} went away with {} piece(s) [{}/{}].",
            new Object[]{
                    peer,
                    availablePieces.cardinality(),
                    this.completedPieces.cardinality(),
                    this.pieces.length
            });
    logger.trace("We now have {} piece(s) and {} outstanding request(s): {}",
            new Object[]{
                    this.completedPieces.cardinality(),
                    this.requestedPieces.cardinality(),
                    this.requestedPieces
            });
    eventDispatcher.notifyPeerDisconnected(peer);
  }

  @Override
  public synchronized void handleIOException(SharingPeer peer,
                                             IOException ioe) {
    eventDispatcher.notifyDownloadFailed(ioe);
  }

  @Override
  public synchronized void handleNewPeerConnected(SharingPeer peer) {
    initIfNecessary(peer);
    eventDispatcher.notifyPeerConnected(peer);
  }

  @Override
  public String toString() {
    return "SharedTorrent{" +
            Arrays.toString(TorrentUtils.getTorrentFileNames(myTorrentMetadata).toArray()) +
            "}";
  }

  @Override
  public String getDirectoryName() {
    return myTorrentMetadata.getDirectoryName();
  }

  @Override
  public List<TorrentFile> getFiles() {
    return myTorrentMetadata.getFiles();
  }

  @Nullable
  @Override
  public List<List<String>> getAnnounceList() {
    return myTorrentMetadata.getAnnounceList();
  }

  @NotNull
  @Override
  public String getAnnounce() {
    return myTorrentMetadata.getAnnounce();
  }

  @Override
  public Optional<Long> getCreationDate() {
    return myTorrentMetadata.getCreationDate();
  }

  @Override
  public Optional<String> getComment() {
    return myTorrentMetadata.getComment();
  }

  @Override
  public Optional<String> getCreatedBy() {
    return myTorrentMetadata.getCreatedBy();
  }

  @Override
  public int getPieceLength() {
    return myTorrentMetadata.getPieceLength();
  }

  @Override
  public byte[] getPiecesHashes() {
    return myTorrentMetadata.getPiecesHashes();
  }

  @Override
  public boolean isPrivate() {
    return myTorrentMetadata.isPrivate();
  }

  @Override
  public int getPiecesCount() {
    return myTorrentMetadata.getPiecesCount();
  }

  @Override
  public byte[] getInfoHash() {
    return myTorrentMetadata.getInfoHash();
  }

  @Override
  public String getHexInfoHash() {
    return myTorrentMetadata.getHexInfoHash();
  }

  @Override
  public int getPieceCount() {
    return getPiecesCount();
  }

  @Override
  public long getPieceSize(int pieceIdx) {
    return getPieceLength();
  }

  public synchronized void savePieceAndValidate(Piece p) throws IOException {
//    p.finish();
  }

  public synchronized void markCompletedAndAddValidationFuture(Piece piece, Future<?> validationFuture) {
    this.markCompleted(piece);
    myValidationFutures.put(piece.getIndex(), validationFuture);
  }

  public synchronized boolean isAllPiecesOfPeerCompletedAndValidated(SharingPeer peer) {
    final BitSet availablePieces = peer.getAvailablePieces();
    for (Piece piece : pieces) {
      final boolean peerHaveCurrentPiece = availablePieces.get(piece.getIndex());
      if (!peerHaveCurrentPiece) continue;
      if (!completedPieces.get(piece.getIndex())) return false;
      if (myValidationFutures.get(piece.getIndex()) != null) return false;
    }
    return true;
  }

  public void addConnectedPeer(SharingPeer sharingPeer) {
    myDownloaders.add(sharingPeer);
  }
}
