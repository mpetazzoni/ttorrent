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

import com.turn.ttorrent.client.PeerInformation;
import com.turn.ttorrent.client.Piece;
import com.turn.ttorrent.client.SharedTorrent;
import com.turn.ttorrent.common.LoggerUtils;
import com.turn.ttorrent.common.Peer;
import com.turn.ttorrent.common.TorrentLoggerFactory;
import com.turn.ttorrent.common.TorrentUtils;
import com.turn.ttorrent.common.protocol.PeerMessage;
import com.turn.ttorrent.network.ConnectionManager;
import com.turn.ttorrent.network.WriteListener;
import com.turn.ttorrent.network.WriteTask;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Serializable;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;


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
public class SharingPeer extends Peer implements MessageListener, PeerInformation {

  private static final Logger logger = TorrentLoggerFactory.getLogger();

  private final Object availablePiecesLock;
  private volatile boolean choking;
  private volatile boolean interesting;
  private volatile boolean choked;
  private volatile boolean interested;
  private final SharedTorrent torrent;
  private final BitSet availablePieces;
  private BitSet poorlyAvailablePieces;
  private final Map<Piece, Integer> myRequestedPieces;

  private volatile boolean downloading;

  private final Rate download;
  private final Rate upload;
  private final AtomicInteger downloadedPiecesCount;
  private final List<PeerActivityListener> listeners;

  private final Object requestsLock;

  private final AtomicBoolean isStopped;

  private final ConnectionManager connectionManager;
  private final ByteChannel socketChannel;

  private final String clientIdentifier;
  private final int clientVersion;

  /**
   * Create a new sharing peer on a given torrent.
   *  @param ip      The peer's IP address.
   * @param port    The peer's port.
   * @param peerId  The byte-encoded peer ID.
   * @param torrent The torrent this peer exchanges with us on.
   * @param clientIdentifier
   * @param clientVersion
   */
  public SharingPeer(String ip,
                     int port,
                     ByteBuffer peerId,
                     SharedTorrent torrent,
                     ConnectionManager connectionManager,
                     PeerActivityListener client,
                     ByteChannel channel,
                     String clientIdentifier,
                     int clientVersion) {
    super(ip, port, peerId);

    this.torrent = torrent;
    this.clientIdentifier = clientIdentifier;
    this.clientVersion = clientVersion;
    this.listeners = Arrays.asList(client, torrent);
    this.availablePieces = new BitSet(torrent.getPieceCount());
    this.poorlyAvailablePieces = new BitSet(torrent.getPieceCount());

    this.requestsLock = new Object();
    this.socketChannel = channel;
    this.isStopped = new AtomicBoolean(false);
    this.availablePiecesLock = new Object();
    this.myRequestedPieces = new HashMap<Piece, Integer>();
    this.connectionManager = connectionManager;
    this.download = new Rate();
    this.upload = new Rate();
    this.setTorrentHash(torrent.getHexInfoHash());
    this.choking = true;
    this.interesting = false;
    this.choked = true;
    this.interested = false;
    this.downloading = false;
    this.downloadedPiecesCount = new AtomicInteger();
  }

  public Rate getDLRate() {
    return this.download;
  }

  public Rate getULRate() {
    return this.upload;
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

  @Override
  public byte[] getId() {
    return getPeerIdArray();
  }

  @Override
  public String getClientIdentifier() {
    return clientIdentifier;
  }

  @Override
  public int getClientVersion() {
    return clientVersion;
  }

  public void onConnectionEstablished() {
    firePeerConnected();
    BitSet pieces = this.torrent.getCompletedPieces();
    if (pieces.cardinality() > 0) {
      this.send(PeerMessage.BitfieldMessage.craft(pieces));
    }
    resetRates();
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
    logger.trace("Unchoking {}", this);
    this.send(PeerMessage.UnchokeMessage.craft());
    this.choking = false;
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

  public BitSet getPoorlyAvailablePieces() {
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
    synchronized (requestsLock) {
      return myRequestedPieces.keySet();
    }
  }

  public void resetRates() {
    this.download.reset();
    this.upload.reset();
  }

  public void pieceDownloaded() {
    downloadedPiecesCount.incrementAndGet();
  }

  public int getDownloadedPiecesCount() {
    return downloadedPiecesCount.get();
  }

  /**
   * Tells whether this peer as an active connection through a peer exchange.
   */
  public boolean isConnected() {
    return this.socketChannel.isOpen();
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
    if (isStopped.getAndSet(true))
      return;

    try {
      connectionManager.closeChannel(socketChannel);
    } catch (IOException e) {
      LoggerUtils.errorAndDebugDetails(logger, "cannot close socket channel. Peer {}", this, e);
    }

    this.firePeerDisconnected();

    synchronized (requestsLock) {
      this.downloading = myRequestedPieces.size() > 0;
      myRequestedPieces.clear();
    }

    this.afterPeerDisconnected();
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
      ByteBuffer data = message.getData();
      data.rewind();
      connectionManager.offerWrite(new WriteTask(socketChannel, data, new WriteListener() {
        @Override
        public void onWriteFailed(String message, Throwable e) {
          if (e == null) {
            logger.debug(message);
          } else {
            logger.debug(message, e);
          }
          unbind(true);
        }

        @Override
        public void onWriteDone() {
        }
      }), 1, TimeUnit.SECONDS);
    } else {
      logger.debug("Attempting to send a message to non-connected peer {}!", this);
      unbind(true);
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
  public void downloadPiece(final Piece piece)
          throws IllegalStateException {
    List<PeerMessage.RequestMessage> toSend = new ArrayList<PeerMessage.RequestMessage>();
    synchronized (this.requestsLock) {
      if (myRequestedPieces.containsKey(piece)) {
        //already requested
        return;
      }
      int requestedPiecesCount = 0;
      int lastRequestedOffset = 0;
      while (lastRequestedOffset < piece.size()) {
        PeerMessage.RequestMessage request = PeerMessage.RequestMessage
                .craft(piece.getIndex(), lastRequestedOffset,
                        Math.min((int) (piece.size() - lastRequestedOffset),
                                PeerMessage.RequestMessage.DEFAULT_REQUEST_SIZE));
        toSend.add(request);
        requestedPiecesCount++;
        lastRequestedOffset = request.getLength() + lastRequestedOffset;
      }
      myRequestedPieces.put(piece, requestedPiecesCount);
      this.downloading = myRequestedPieces.size() > 0;
    }
    for (PeerMessage.RequestMessage requestMessage : toSend) {
      this.send(requestMessage);
    }
  }

  public boolean isDownloading() {
    return this.downloading;
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
   * @param piece The piece of PIECE message received.
   */
  private void removeBlockRequest(final Piece piece) {
    synchronized (this.requestsLock) {
      Integer requestedBlocksCount = myRequestedPieces.get(piece);
      if (requestedBlocksCount == null) {
        return;
      }
      if (requestedBlocksCount <= 1) {
        //it's last block
        myRequestedPieces.remove(piece);
      } else {
        myRequestedPieces.put(piece, requestedBlocksCount - 1);
      }
      this.downloading = myRequestedPieces.size() > 0;
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
  public void cancelPendingRequests() {
    cancelPendingRequests(null);
  }

  public void cancelPendingRequests(@Nullable final Piece piece) {
    synchronized (this.requestsLock) {
      if (piece != null) {
        myRequestedPieces.remove(piece);
      } else {
        myRequestedPieces.clear();
      }
      this.downloading = myRequestedPieces.size() > 0;
    }
  }

  public int getRemainingRequestedPieces(final Piece piece) {
    synchronized (this.requestsLock) {
      Integer requestedBlocksCount = myRequestedPieces.get(piece);
      if (requestedBlocksCount == null) return 0;
      return requestedBlocksCount;
    }
  }

  /**
   * Handle an incoming message from this peer.
   *
   * @param msg The incoming, parsed message.
   */
  @Override
  public void handleMessage(PeerMessage msg) {
//    logger.trace("Received msg {} from {}", msg.getType(), this);
    if (isStopped.get())
      return;
    if (!torrent.isInitialized()) {
      torrent.initIfNecessary(this);
    }
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
        if (this.choking) {
          unchoke();
        }
        break;
      case NOT_INTERESTED:
        this.interested = false;
        if (!interesting) {
          unbind(true);
        }
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
                Arrays.toString(TorrentUtils.getTorrentFileNames(torrent).toArray()),
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

          ByteBuffer bufferForMessage = PeerMessage.PieceMessage.createBufferWithHeaderForMessage(
                  request.getPiece(), request.getOffset(), request.getLength());

          rp.read(request.getOffset(), request.getLength(), bufferForMessage);

          this.send(PeerMessage.PieceMessage.craft(request.getPiece(),
                  request.getOffset(), bufferForMessage));
          this.upload.add(request.getLength());

          if (request.getOffset() + request.getLength() == rp.size()) {
            this.firePieceSent(rp);
          }
        } catch (IOException ioe) {
          logger.debug("error", ioe);
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

        logger.trace("Got piece ({} {}@{}) from {}", new Object[]{
                p.getIndex(),
                p.size(),
                piece.getOffset(),
                this
        });

        this.download.add(piece.getBlock().capacity());

        try {
          boolean isPieceDownloaded = false;
          synchronized (p) {
            // Remove the corresponding request from the request queue to
            //  make room for next block requests.
            this.removeBlockRequest(p);
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
            if (getRemainingRequestedPieces(p) == 0) {
              this.firePieceCompleted(p);
              isPieceDownloaded = true;
            }
          }
          if (isPieceDownloaded) {
            firePeerReady();
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

  private void afterPeerDisconnected() {
    for (PeerActivityListener listener : this.listeners) {
      listener.afterPeerRemoved(this);
    }
  }

  private void firePeerConnected() {
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

  public SharedTorrent getTorrent() {
    return this.torrent;
  }

  public int getDownloadingPiecesCount() {
    synchronized (requestsLock) {
      return myRequestedPieces.size();
    }
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
}
