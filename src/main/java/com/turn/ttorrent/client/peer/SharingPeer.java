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
package com.turn.ttorrent.client.peer;

import com.turn.ttorrent.client.Client;
import com.turn.ttorrent.client.Piece;
import com.turn.ttorrent.client.SharedTorrent;
import com.turn.ttorrent.common.Cleanable;
import com.turn.ttorrent.common.Peer;
import com.turn.ttorrent.common.TorrentHash;
import com.turn.ttorrent.common.protocol.PeerMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Serializable;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.*;
import java.util.concurrent.*;


/**
 * A peer exchanging on a torrent with the BitTorrent client.
 * <p/>
 * <p>
 * A SharingPeer extends the base Peer class with all the data and logic needed
 * by the BitTorrent client to interact with a peer exchanging on the same
 * torrent.
 * </p>
 * <p/>
 * <p>
 * Peers are defined by their peer ID, IP address and port number, just like
 * base peers. Peers we exchange with also contain four crucial attributes:
 * </p>
 * <p/>
 * <ul>
 * <li><code>choking</code>, which means we are choking this peer and we're
 * not willing to send him anything for now;</li>
 * <li><code>interesting</code>, which means we are interested in a piece
 * this peer has;</li>
 * <li><code>choked</code>, if this peer is choking and won't send us
 * anything right now;</li>
 * <li><code>interested</code>, if this peer is interested in something we
 * have.</li>
 * </ul>
 * <p/>
 * <p>
 * Peers start choked and uninterested.
 * </p>
 *
 * @author mpetazzoni
 */
public class SharingPeer extends Peer implements MessageListener, SharingPeerInfo, Cleanable {

  private static final Logger logger = LoggerFactory.getLogger(SharingPeer.class);
  private static final int MAX_PIPELINED_REQUESTS = 100;
  private static final long MAX_REQUEST_TIMEOUT = 20*1000;

  private final Object availablePiecesLock;
  private volatile boolean choking;
  private volatile boolean interesting;
  private volatile boolean choked;
  private volatile boolean interested;
  private SharedTorrent torrent;
  private BitSet availablePieces;
  private BitSet poorlyAvailablePieces;
  private final ConcurrentMap<Piece, Integer> myRequestedPieces;
//  private int lastRequestedOffset;

  private final BlockingQueue<PeerMessage.RequestMessage> myRequests;
  private volatile boolean downloading;

  private PeerExchange exchange = null;
  private Rate download;
  private Rate upload;
  private Set<PeerActivityListener> listeners;

  private final Object requestsLock, exchangeLock;

  private volatile Future connectTask;
  private volatile boolean isStopped = false;

  public SharingPeer(Peer peer, SharedTorrent torrent){
    this(peer.getIp(), peer.getPort(), peer.getPeerId(), torrent);
  }

  /**
   * Create a new sharing peer on a given torrent.
   *
   * @param ip      The peer's IP address.
   * @param port    The peer's port.
   * @param peerId  The byte-encoded peer ID.
   * @param torrent The torrent this peer exchanges with us on.
   */
  public SharingPeer(String ip, int port, ByteBuffer peerId, SharedTorrent torrent) {
    super(ip, port, peerId);

    this.torrent = torrent;
    this.listeners = new HashSet<PeerActivityListener>();
    this.availablePieces = new BitSet(torrent.getPieceCount());
    this.poorlyAvailablePieces = new BitSet(torrent.getPieceCount());

    this.requestsLock = new Object();
    this.exchangeLock = new Object();
    this.availablePiecesLock = new Object();
    this.myRequestedPieces = new ConcurrentHashMap<Piece, Integer>();
    myRequests = new LinkedBlockingQueue<PeerMessage.RequestMessage>(SharingPeer.MAX_PIPELINED_REQUESTS);
    this.reset();
  }

  /**
   * Register a new peer activity listener.
   *
   * @param listener The activity listener that wants to receive events from
   *                 this peer's activity.
   */
  public void register(PeerActivityListener listener) {
    this.listeners.add(listener);
  }

  public Rate getDLRate() {
    return this.download;
  }

  public Rate getULRate() {
    return this.upload;
  }

  /**
   * Reset the peer state.
   * <p/>
   * <p>
   * Initially, peers are considered choked, choking, and neither interested
   * nor interesting.
   * </p>
   */
  public void reset() {
    synchronized (this) {
      this.choking = true;
      this.interesting = false;
      this.choked = true;
      this.interested = false;
      this.myRequestedPieces.clear();
      this.myRequests.clear();
      this.downloading = false;
    }

    synchronized (this.exchangeLock) {
      this.exchange = null;
    }
  }

  /**
   * Choke this peer.
   * <p/>
   * <p>
   * We don't want to upload to this peer anymore, so mark that we're choking
   * from this peer.
   * </p>
   */
  public void choke() {
    if (!this.choking) {
      logger.trace("Choking {}", this);
      this.send(PeerMessage.ChokeMessage.craft());
      this.choking = true;
    }
  }

  /**
   * Unchoke this peer.
   * <p/>
   * <p>
   * Mark that we are no longer choking from this peer and can resume
   * uploading to it.
   * </p>
   */
  public void unchoke() {
    if (this.choking) {
      logger.trace("Unchoking {}", this);
      this.send(PeerMessage.UnchokeMessage.craft());
      this.choking = false;
    }
  }

  public boolean isChoking() {
    return this.choking;
  }

  public void interesting() {
    if (!this.interesting) {
      logger.trace("Telling {} we're interested.", this);
      this.send(PeerMessage.InterestedMessage.craft());
      this.interesting = true;
    }
  }

  public void notInteresting() {
    if (this.interesting) {
      logger.trace("Telling {} we're no longer interested.", this);
      this.send(PeerMessage.NotInterestedMessage.craft());
      this.interesting = false;
    }
  }

  public boolean isInteresting() {
    return this.interesting;
  }

  public boolean isChoked() {
    return this.choked;
  }

  public boolean isInterested() {
    return this.interested;
  }

  public BitSet getPoorlyAvailablePieces(){
    return poorlyAvailablePieces;
  }

  /**
   * Returns the available pieces from this peer.
   *
   * @return A clone of the available pieces bit field from this peer.
   */
  public BitSet getAvailablePieces() {
    synchronized (this.availablePiecesLock) {
      return (BitSet) this.availablePieces.clone();
    }
  }

  /**
   * Returns the currently requested piece, if any.
   */
  public Set<Piece> getRequestedPieces() {
    return myRequestedPieces.keySet();
  }

  /**
   * Tells whether this peer is a seed.
   *
   * @return Returns <em>true</em> if the peer has all of the torrent's pieces
   * available.
   */
  public synchronized boolean isSeed() {
    return this.torrent.getPieceCount() > 0 &&
      this.getAvailablePieces().cardinality() ==
        this.torrent.getPieceCount();
  }

  /**
   * Bind a connected socket to this peer.
   * <p/>
   * <p>
   * This will create a new peer exchange with this peer using the given
   * socket, and register the peer as a message listener.
   * </p>
   *
   * @param channel The connected socket channel for this peer.
   */
  public synchronized void bind(SocketChannel channel) throws SocketException {
    Client.cleanupProcessor().registerCleanable(this);
    firePeerConnected();
    synchronized (this.exchangeLock) {
      this.exchange = new PeerExchange(this, this.torrent, channel);
      this.exchange.register(this);
      //TODO if I start before registration, some messages may be skipped. It means that we must recheck whether certain
      // torrent exist, even if we didn't get information about them initially.
      this.exchange.start();
    }
    resetRates();
  }

  public synchronized void resetRates() {
    this.download = new Rate();
    this.download.reset();

    this.upload = new Rate();
    this.upload.reset();
  }

  /**
   * Tells whether this peer as an active connection through a peer exchange.
   */
  public boolean isConnected() {
    synchronized (this.exchangeLock) {
      return this.exchange != null && this.exchange.isConnected();
    }
  }

  /**
   * Unbind and disconnect this peer.
   * <p/>
   * <p>
   * This terminates the eventually present and/or connected peer exchange
   * with the peer and fires the peer disconnected event to any peer activity
   * listeners registered on this peer.
   * </p>
   *
   * @param force Force unbind without sending cancel requests.
   */
  public void unbind(boolean force) {
    if (isStopped)
      return;
    isStopped = true;
    Client.cleanupProcessor().unregisterCleanable(this);
    if (!force) {
      // Cancel all outgoing requests, and send a NOT_INTERESTED message to
      // the peer.
      try {
        this.cancelPendingRequests();
        this.send(PeerMessage.NotInterestedMessage.craft());
      } catch (Exception ex) {
      }
    }

    PeerExchange exchangeCopy;
    synchronized (this.exchangeLock) {
      exchangeCopy = exchange;
    }

    if (exchangeCopy != null) {
      exchangeCopy.close();
    }

    synchronized (this.exchangeLock) {
      this.exchange = null;
    }

    this.firePeerDisconnected();
    reset();
  }

  /**
   * Send a message to the peer.
   * <p/>
   * <p>
   * Delivery of the message can only happen if the peer is connected.
   * </p>
   *
   * @param message The message to send to the remote peer through our peer
   *                exchange.
   */
  public void send(PeerMessage message) throws IllegalStateException {
    logger.trace("Sending msg {} to {}", message.getType(), this);
    if (this.isConnected()) {
      this.exchange.send(message);
    } else {
      logger.info("Attempting to send a message to non-connected peer {}!", this);
    }
  }

  /**
   * Download the given piece from this peer.
   * <p/>
   * <p>
   * Starts a block request queue and pre-fill it with MAX_PIPELINED_REQUESTS
   * block requests.
   * </p>
   * <p/>
   * <p>
   * Further requests will be added, one by one, every time a block is
   * returned.
   * </p>
   *
   * @param piece The piece chosen to be downloaded from this peer.
   */
  public synchronized void downloadPiece(final Piece piece){
    downloadPiece(piece, false);
  }

  public void downloadPiece(final Piece piece, boolean force)
    throws IllegalStateException {
    synchronized (this.requestsLock) {
      if (!myRequestedPieces.containsKey(piece) || force) {
        myRequestedPieces.put(piece, 0);
      }
    }
    this.requestNextBlocksForPiece(piece);
  }

  public boolean isPieceDownloading(final Piece piece){
    return myRequestedPieces.get(piece) != null;
  }

  public synchronized boolean isDownloading() {
    return this.downloading;
  }

  /**
   * Request some more blocks from this peer.
   * <p/>
   * <p>
   * Re-fill the pipeline to get download the next blocks from the peer.
   * </p>
   */
  private void requestNextBlocksForPiece(final Piece piece) {
    synchronized (this.requestsLock) {
      while ( myRequestedPieces.get(piece) < piece.size()) {
        final int lastRequestedOffset = myRequestedPieces.get(piece);
        PeerMessage.RequestMessage request = PeerMessage.RequestMessage
          .craft(piece.getIndex(),lastRequestedOffset,
            Math.min((int) (piece.size() - lastRequestedOffset),
              PeerMessage.RequestMessage.DEFAULT_REQUEST_SIZE));
        removeBlockRequest(piece.getIndex(), lastRequestedOffset);
        myRequests.add(request);
//        logger.debug("---------Queue size: " + myRequests.size());
        this.send(request);
        myRequestedPieces.put(piece, request.getLength() + lastRequestedOffset);
      }
      this.downloading = myRequests.size() > 0;
    }
  }

  /**
   * Remove the REQUEST message from the request pipeline matching this
   * PIECE message.
   * <p/>
   * <p>
   * Upon reception of a piece block with a PIECE message, remove the
   * corresponding request from the pipeline to make room for the next block
   * requests.
   * </p>
   *
   * @param pieceIdx The piece index of PIECE message received.
   * @param offset The offset of PIECE message received.
   */
  private void removeBlockRequest(final int pieceIdx, final int offset) {
    synchronized (this.requestsLock) {
      for (PeerMessage.RequestMessage request : myRequests) {
        if (request.getPiece() == pieceIdx && request.getOffset() == offset) {
          myRequests.remove(request);
          break;
        }
      }
      this.downloading = myRequests.size() > 0;
    }
  }

  /**
   * Cancel all pending requests.
   * <p/>
   * <p>
   * This queues CANCEL messages for all the requests in the queue, and
   * returns the list of requests that were in the queue.
   * </p>
   * <p/>
   * <p>
   * If no request queue existed, or if it was empty, an empty set of request
   * messages is returned.
   * </p>
   */
  public Set<PeerMessage.RequestMessage> cancelPendingRequests() {
    return cancelPendingRequests(null);
  }

  public Set<PeerMessage.RequestMessage> cancelPendingRequests(final Piece piece) {
    synchronized (this.requestsLock) {
      Set<PeerMessage.RequestMessage> cancelled =
        new HashSet<PeerMessage.RequestMessage>();

      for (PeerMessage.RequestMessage request : myRequests) {
        if (piece == null || piece.getIndex() == request.getPiece()) {
          this.send(PeerMessage.CancelMessage.craft(request.getPiece(),
                  request.getOffset(), request.getLength()));
          cancelled.add(request);
        }
      }

      myRequests.removeAll(cancelled);
      this.downloading = myRequests.size() > 0;

      return cancelled;
    }
  }

  public Set<PeerMessage.RequestMessage> getRemainingRequestedPieces(final Piece piece){
    synchronized (this.requestsLock) {
      Set<PeerMessage.RequestMessage> pieceParts =
              new HashSet<PeerMessage.RequestMessage>();

      for (PeerMessage.RequestMessage request : myRequests) {
        if (piece.getIndex() == request.getPiece()) {
          pieceParts.add(request);
        }
      }

      return pieceParts;
    }
  }

  /**
   * Handle an incoming message from this peer.
   *
   * @param msg The incoming, parsed message.
   */
  @Override
  public synchronized void handleMessage(PeerMessage msg) {
//    logger.trace("Received msg {} from {}", msg.getType(), this);
    if (isStopped)
      return;
    switch (msg.getType()) {
      case KEEP_ALIVE:
        // Nothing to do, we're keeping the connection open anyways.
        break;
      case CHOKE:
        this.choked = true;
        this.firePeerChoked();
        this.cancelPendingRequests();
        break;
      case UNCHOKE:
        this.choked = false;
        logger.trace("Peer {} is now accepting requests.", this);
        this.firePeerReady();
        break;
      case INTERESTED:
        this.interested = true;
        break;
      case NOT_INTERESTED:
        this.interested = false;
        break;
      case HAVE:
        // Record this peer has the given piece
        PeerMessage.HaveMessage have = (PeerMessage.HaveMessage) msg;
        Piece havePiece = this.torrent.getPiece(have.getPieceIndex());

        synchronized (this.availablePiecesLock) {
          this.availablePieces.set(havePiece.getIndex());
          logger.trace("Peer {} now has {} [{}/{}].",
            new Object[]{
              this,
              havePiece,
              this.availablePieces.cardinality(),
              this.torrent.getPieceCount()
            });
        }

        this.firePieceAvailabity(havePiece);
        break;
      case BITFIELD:
        // Augment the hasPiece bit field from this BITFIELD message
        PeerMessage.BitfieldMessage bitfield =
          (PeerMessage.BitfieldMessage) msg;

        synchronized (this.availablePiecesLock) {
          this.availablePieces.or(bitfield.getBitfield());
          logger.trace("Recorded bitfield from {} with {} " +
            "pieces(s) [{}/{}].",
            new Object[]{
              this,
              bitfield.getBitfield().cardinality(),
              this.availablePieces.cardinality(),
              this.torrent.getPieceCount()
            });
        }

        this.fireBitfieldAvailabity();
        break;
      case REQUEST:
        PeerMessage.RequestMessage request =
          (PeerMessage.RequestMessage) msg;
        logger.trace("Got request message for {} ({} {}@{}) from {}", new Object[]{
          Arrays.toString(torrent.getFilenames().toArray()),
          request.getPiece(),
          request.getLength(),
          request.getOffset(),
          this
        });
        Piece rp = this.torrent.getPiece(request.getPiece());

        // If we are choking from this peer and it still sends us
        // requests, it is a violation of the BitTorrent protocol.
        // Similarly, if the peer requests a piece we don't have, it
        // is a violation of the BitTorrent protocol. In these
        // situation, terminate the connection.
        if (!rp.isValid()) {
          logger.warn("Peer {} violated protocol, terminating exchange: " + this.isChoking() + " " + rp.isValid(), this);
          this.unbind(true);
          break;
        }

        if (request.getLength() >
          PeerMessage.RequestMessage.MAX_REQUEST_SIZE) {
          logger.warn("Peer {} requested a block too big, terminating exchange.", this);
          this.unbind(true);
          break;
        }

        // At this point we agree to send the requested piece block to
        // the remote peer, so let's queue a message with that block
        try {
          ByteBuffer block = rp.read(request.getOffset(),
            request.getLength());
          this.send(PeerMessage.PieceMessage.craft(request.getPiece(),
            request.getOffset(), block));
          this.upload.add(block.capacity());

          if (request.getOffset() + request.getLength() == rp.size()) {
            this.firePieceSent(rp);
          }
        } catch (IOException ioe) {
          logger.error("error", ioe);
          this.fireIOException(new IOException(
            "Error while sending piece block request!", ioe));
        }

        break;
      case PIECE:
        // Record the incoming piece block.

        // Should we keep track of the requested pieces and act when we
        // get a piece we didn't ask for, or should we just stay
        // greedy?
        PeerMessage.PieceMessage piece = (PeerMessage.PieceMessage) msg;
        Piece p = this.torrent.getPiece(piece.getPiece());

        logger.trace("Got piece for {} ({} {}@{}) from {}", new Object[]{
          Arrays.toString(torrent.getFilenames().toArray()),
          p.getIndex(),
          p.size(),
          piece.getOffset(),
          this
        });


        // Remove the corresponding request from the request queue to
        //  make room for next block requests.
        this.removeBlockRequest(piece.getPiece(), piece.getOffset());
        this.download.add(piece.getBlock().capacity());

        try {
          synchronized (p) {
            if (p.isValid()) {
              this.cancelPendingRequests(p);
              this.firePeerReady();
              logger.debug("Discarding block for already completed " + p);
              break;
            }
            //TODO add proper catch for IOException
            p.record(piece.getBlock(), piece.getOffset());

            // If the block offset equals the piece size and the block
            // length is 0, it means the piece has been entirely
            // downloaded. In this case, we have nothing to save, but
            // we should validate the piece.
            if (getRemainingRequestedPieces(p).size() == 0) {
              p.finish();
              p.validate(torrent, p);
              this.firePieceCompleted(p);
              myRequestedPieces.remove(p);
              this.firePeerReady();
            } else {
              if (piece.getOffset() + piece.getBlock().capacity()
                == p.size()) { // final request reached
                for (PeerMessage.RequestMessage requestMessage : getRemainingRequestedPieces(p)) {
                  send(requestMessage);
                }
              } else {
                this.requestNextBlocksForPiece(p);
              }
            }
          }
        } catch (IOException ioe) {
          logger.error(ioe.getMessage(), ioe);
          this.fireIOException(new IOException(
            "Error while storing received piece block!", ioe));
          break;
        }
        break;
      case CANCEL:
        // No need to support
        break;
    }
  }

  /**
   * Fire the peer choked event to all registered listeners.
   * <p/>
   * <p>
   * The event contains the peer that chocked.
   * </p>
   */
  private void firePeerChoked() {
    for (PeerActivityListener listener : this.listeners) {
      listener.handlePeerChoked(this);
    }
  }

  /**
   * Fire the peer ready event to all registered listeners.
   * <p/>
   * <p>
   * The event contains the peer that unchoked or became ready.
   * </p>
   */
  private void firePeerReady() {
    for (PeerActivityListener listener : this.listeners) {
      listener.handlePeerReady(this);
    }
  }

  /**
   * Fire the piece availability event to all registered listeners.
   * <p/>
   * <p>
   * The event contains the peer (this), and the piece that became available.
   * </p>
   */
  private void firePieceAvailabity(Piece piece) {
    for (PeerActivityListener listener : this.listeners) {
      listener.handlePieceAvailability(this, piece);
    }
  }

  /**
   * Fire the bit field availability event to all registered listeners.
   * <p/>
   * The event contains the peer (this), and the bit field of available pieces
   * from this peer.
   */
  private void fireBitfieldAvailabity() {
    for (PeerActivityListener listener : this.listeners) {
      listener.handleBitfieldAvailability(this,
              this.getAvailablePieces());
    }
  }

  /**
   * Fire the piece sent event to all registered listeners.
   * <p/>
   * <p>
   * The event contains the peer (this), and the piece number that was
   * sent to the peer.
   * </p>
   *
   * @param piece The completed piece.
   */
  private void firePieceSent(Piece piece) {
    for (PeerActivityListener listener : this.listeners) {
      listener.handlePieceSent(this, piece);
    }
  }

  /**
   * Fire the piece completion event to all registered listeners.
   * <p/>
   * <p>
   * The event contains the peer (this), and the piece number that was
   * completed.
   * </p>
   *
   * @param piece The completed piece.
   */
  private void firePieceCompleted(Piece piece) throws IOException {
    for (PeerActivityListener listener : this.listeners) {
      listener.handlePieceCompleted(this, piece);
    }
  }

  /**
   * Fire the peer disconnected event to all registered listeners.
   * <p/>
   * <p>
   * The event contains the peer that disconnected (this).
   * </p>
   */
  private void firePeerDisconnected() {
    for (PeerActivityListener listener : this.listeners) {
      listener.handlePeerDisconnected(this);
    }
  }

  private void firePeerConnected(){
    for (PeerActivityListener listener : this.listeners) {
      listener.handleNewPeerConnected(this);
    }
  }

  /**
   * Fire the IOException event to all registered listeners.
   * <p/>
   * <p>
   * The event contains the peer that triggered the problem, and the
   * exception object.
   * </p>
   */
  private void fireIOException(IOException ioe) {
    for (PeerActivityListener listener : this.listeners) {
      listener.handleIOException(this, ioe);
    }
  }

  public Future getConnectTask() {
    return this.connectTask;
  }

  public void setConnectTask(Future connectTask) {
    Future old = this.connectTask;
    if (old != null) old.cancel(true);
    this.connectTask = connectTask;
  }

  public String toString() {
    return new StringBuilder(super.toString())
      .append(" [")
      .append((this.choked ? "C" : "c"))
      .append((this.interested ? "I" : "i"))
      .append("|")
      .append((this.choking ? "C" : "c"))
      .append((this.interesting ? "I" : "i"))
      .append("|")
      .append(this.availablePieces.cardinality())
      .append("]")
      .toString();
  }

  public String getTorrentHexInfoHash() {
    return this.torrent.getHexInfoHash();
  }

  public SharedTorrent getTorrent() {
    return this.torrent;
  }

  public SocketChannel getSocketChannel() {
    return exchange.getChannel();
  }

  public int getDownloadingPiecesCount(){
    return myRequestedPieces.size();
  }
  @Override
  public TorrentHash getTorrentHash() {
    return torrent;
  }

  @Override
  public void cleanUp() {
    // temporary ignore until I figure out how to handle this in more robust way.
/*
    for (PeerMessage.RequestMessage request : myRequests) {
      if (System.currentTimeMillis() - request.getSendTime() > MAX_REQUEST_TIMEOUT){
        send(request);
        request.renew();
      }
    }
*/
  }

  /**
   * Download rate comparator.
   * <p/>
   * <p>
   * Compares sharing peers based on their current download rate.
   * </p>
   *
   * @author mpetazzoni
   * @see Rate.RateComparator
   */
  public static class DLRateComparator
    implements Comparator<SharingPeer>, Serializable {

    private static final long serialVersionUID = 96307229964730L;

    public int compare(SharingPeer a, SharingPeer b) {
      return Rate.RATE_COMPARATOR.compare(a.getDLRate(), b.getDLRate());
    }
  }

  /**
   * Upload rate comparator.
   * <p/>
   * <p>
   * Compares sharing peers based on their current upload rate.
   * </p>
   *
   * @author mpetazzoni
   * @see Rate.RateComparator
   */
  public static class ULRateComparator
    implements Comparator<SharingPeer>, Serializable {

    private static final long serialVersionUID = 38794949747717L;

    public int compare(SharingPeer a, SharingPeer b) {
      return Rate.RATE_COMPARATOR.compare(a.getULRate(), b.getULRate());
    }
  }
}
