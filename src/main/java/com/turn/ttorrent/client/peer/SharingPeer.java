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

import com.google.common.collect.Iterators;
import com.turn.ttorrent.client.Client;
import com.turn.ttorrent.common.Peer;
import com.turn.ttorrent.client.Piece;
import com.turn.ttorrent.client.PieceBlock;
import com.turn.ttorrent.client.SharedTorrent;

import com.turn.ttorrent.client.io.PeerMessage;
import io.netty.channel.socket.SocketChannel;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.BitSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Queue;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;
import javax.annotation.concurrent.GuardedBy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A peer exchanging on a torrent with the BitTorrent client.
 *
 * <p>
 * A SharingPeer embeds the base Peer class with all the data and logic needed
 * by the BitTorrent client to interact with a peer exchanging on the same
 * torrent.
 * </p>
 *
 * <p>
 * Peers are defined by their peer ID, IP address and port number, just like
 * base peers. Peers we exchange with also contain four crucial attributes:
 * </p>
 *
 * <ul>
 *   <li><code>choked</code>, if the peer is choked, and we are
 *   not willing to send him anything for now;</li>
 *   <li><code>interesting</code>, if the peer has a piece which is
 *   interesting to us.</li>
 *   <li><code>choking</code>, if this peer is choking and won't send us
 *   anything right now;</li>
 *   <li><code>interested</code>, if this peer is interested in something we
 *   have.</li>
 * </ul>
 *
 * <p>
 * Peers start choked and uninterested.
 * </p>
 *
 * @author mpetazzoni
 */
public class SharingPeer implements PeerMessageListener {

    private static final Logger logger = LoggerFactory.getLogger(SharingPeer.class);
    private static final int MAX_PIPELINED_REQUESTS = 5;
    private final SharedTorrent torrent;
    private final Peer peer;
    private final PeerActivityListener listener;    // torrent.getPeerHandler()
    @GuardedBy("lock")
    private final BitSet availablePieces;
    // TODO: AtomicBoolean Or AtomicBoolean for CompareAndSet?
    // We decide about them:
    private volatile boolean choked;
    private volatile boolean interesting;
    // They decide about us:
    private volatile boolean choking;
    private volatile boolean interested;
    private volatile SocketChannel channel;
    // @GuardedBy("requestsLock")
    // private final BlockingQueue<PeerMessage.RequestMessage> requests = new ArrayBlockingQueue<PeerMessage.RequestMessage>(SharingPeer.MAX_PIPELINED_REQUESTS);
    private final EWMA download = new EWMA(60);
    private final EWMA upload = new EWMA(60);
    private final Object lock = new Object();
    @Nonnull
    @GuardedBy("lock")
    private Iterator<PeerMessage.RequestMessage> requestsPending = Iterators.emptyIterator();
    @GuardedBy("lock")
    private final Queue<PeerMessage.RequestMessage> requestsSent = new LinkedList<PeerMessage.RequestMessage>();

    /**
     * Create a new sharing peer on a given torrent.
     *
     * @param ip The peer's IP address.
     * @param port The peer's port.
     * @param peer The peer.
     * @param torrent The torrent this peer exchanges with us on.
     */
    public SharingPeer(@Nonnull SharedTorrent torrent, @Nonnull Peer peer, @Nonnull PeerActivityListener listener) {
        this.torrent = torrent;
        this.peer = peer;
        this.listener = listener;

        this.availablePieces = new BitSet(torrent.getPieceCount());

        this.reset();
    }

    @Nonnull
    public Client getClient() {
        return getTorrent().getClient();
    }

    @Nonnull
    public SharedTorrent getTorrent() {
        return torrent;
    }

    @Nonnull
    public Peer getPeer() {
        return peer;
    }

    @Nonnull
    public String getHostIdentifier() {
        return peer.getHostIdentifier();
    }

    @Nonnull
    public String getHexPeerId() {
        return peer.getHexPeerId();
    }

    /**
     * Reset the peer state.
     *
     * <p>
     * Initially, peers are considered choked, choking, and neither interested
     * nor interesting.
     * </p>
     */
    public synchronized void reset() {
        this.choking = true;
        this.interesting = false;
        this.choked = true;
        this.interested = false;

        this.requestsPending = Iterators.emptyIterator();
        this.requestsSent.clear();
    }

    @Nonnull
    public EWMA getDLRate() {
        return this.download;
    }

    @Nonnull
    public EWMA getULRate() {
        return this.upload;
    }

    // TODO: These four methods need to be atomic.
    /**
     * Choke this peer.
     *
     * <p>
     * We don't want to upload to this peer anymore, so mark that we're choking
     * from this peer.
     * </p>
     */
    public void choke() {
        if (!this.choked) {
            logger.trace("Choking {}", this);
            this.send(new PeerMessage.ChokeMessage());
            this.choked = true;
        }
    }

    /**
     * Unchoke this peer.
     *
     * <p>
     * Mark that we are no longer choking from this peer and can resume
     * uploading to it.
     * </p>
     */
    public void unchoke() {
        if (this.choked) {
            logger.trace("Unchoking {}", this);
            this.send(new PeerMessage.UnchokeMessage());
            this.choked = false;
        }
    }

    public boolean isChoked() {
        return this.choked;
    }

    public void interesting() {
        if (!this.interesting) {
            logger.trace("Telling {} we're interested.", this);
            this.send(new PeerMessage.InterestedMessage());
            this.interesting = true;
        }
    }

    public void notInteresting() {
        if (this.interesting) {
            logger.trace("Telling {} we're no longer interested.", this);
            this.send(new PeerMessage.NotInterestedMessage());
            this.interesting = false;
        }
    }

    public boolean isInteresting() {
        return this.interesting;
    }

    public boolean isChoking() {
        return this.choking;
    }

    public boolean isInterested() {
        return this.interested;
    }

    public boolean isConnected() {
        return this.channel != null;
    }

    public void setChannel(@CheckForNull SocketChannel channel) {
        // TODO: Close any preexisting channel.
        this.channel = channel;
    }

    /**
     * Returns the available pieces from this peer.
     *
     * @return A clone of the available pieces bit field from this peer.
     */
    @Nonnull
    public BitSet getAvailablePieces() {
        synchronized (lock) {
            return (BitSet) this.availablePieces.clone();
        }
    }

    @Nonnegative
    public int getAvailablePieceCount() {
        synchronized (lock) {
            return availablePieces.cardinality();
        }
    }

    /**
     * Tells whether this peer is a seed.
     *
     * @return Returns <em>true</em> if the peer has all of the torrent's pieces
     * available.
     */
    public boolean isSeed() {
        long pieceCount = torrent.getPieceCount();
        return pieceCount > 0 && getAvailablePieceCount() == pieceCount;
    }

    /**
     * Send a message to the peer.
     *
     * <p>
     * Delivery of the message can only happen if the peer is connected.
     * </p>
     *
     * @param message The message to send to the remote peer through our peer
     * exchange.
     */
    public void send(@Nonnull PeerMessage message) throws IllegalStateException {
        if (isConnected()) {
            channel.write(message);
        } else {
            logger.warn("Attempting to send a message to non-connected peer {}! ({})", this, message);
        }
    }

    public void close() {
        channel.close();
        channel = null;
    }

    /**
     * Remove the REQUEST message from the request pipeline matching this
     * PIECE message.
     *
     * <p>
     * Upon reception of a piece block with a PIECE message, remove the
     * corresponding request from the pipeline to make room for the next block
     * requests.
     * </p>
     *
     * @param message The PIECE message received.
     */
    private void removeBlockRequest(PeerMessage.PieceMessage response) {
        synchronized (lock) {
            Iterator<PeerMessage.RequestMessage> it = requestsSent.iterator();
            while (it.hasNext()) {
                PeerMessage.RequestMessage request = it.next();
                if (response.answers(request))
                    it.remove();
            }
        }
    }

    /**
     * Cancel all pending requests.
     *
     * <p>
     * This queues CANCEL messages for all the requests in the queue, and
     * returns the list of requests that were in the queue.
     * </p>
     *
     * <p>
     * If no request queue existed, or if it was empty, an empty set of request
     * messages is returned.
     * </p>
     */
    public int cancelPendingRequests() {
        synchronized (lock) {
            int count = 0;
            for (PeerMessage.RequestMessage request : requestsSent) {
                send(new PeerMessage.CancelMessage(request));
                count++;
            }
            requestsSent.clear();
            return count;
        }
    }

    /**
     * Run one step of the SharingPeer finite state machine.
     *
     * <p>
     * Re-fill the pipeline to get download the next blocks from the peer.
     * </p>
     */
    private void run() {
        synchronized (lock) {
            while (requestsSent.size() < MAX_PIPELINED_REQUESTS) {
                if (!channel.isWritable())
                    break;
                if (!requestsPending.hasNext())
                    requestsPending = torrent.newRequestIterator();
                if (!requestsPending.hasNext()) {
                    notInteresting();
                    return;
                }
                interesting();
                PeerMessage.RequestMessage request = requestsPending.next();
                requestsSent.add(request);
                send(request);
            }
        }
    }

    /**
     * Handle an incoming message from this peer.
     *
     * @param msg The incoming, parsed message.
     */
    @Override
    public void handleMessage(PeerMessage msg) {
        try {
            switch (msg.getType()) {
                case KEEP_ALIVE:
                    // Nothing to do, we're keeping the connection open anyways.
                    break;

                case CHOKE:
                    this.choking = true;
                    logger.trace("Peer {} is no longer accepting requests.", this);
                    this.cancelPendingRequests();
                    listener.handlePeerChoking(this);
                    break;

                case UNCHOKE:
                    this.choking = false;
                    logger.trace("Peer {} is now accepting requests.", this);
                    listener.handlePeerUnchoking(this);
                    run();  // We might want something.
                    break;

                case INTERESTED:
                    this.interested = true;
                    logger.trace("Peer {} is now interested.", this);
                    break;

                case NOT_INTERESTED:
                    this.interested = false;
                    logger.trace("Peer {} is no longer interested.", this);
                    break;

                case HAVE: {
                    // Record this peer has the given piece
                    PeerMessage.HaveMessage message = (PeerMessage.HaveMessage) msg;
                    Piece piece = this.torrent.getPiece(message.getPiece());

                    synchronized (lock) {
                        this.availablePieces.set(message.getPiece());
                        logger.trace("Peer {} now has {} [{}/{}].",
                                new Object[]{
                            this,
                            piece,
                            this.availablePieces.cardinality(),
                            this.torrent.getPieceCount()
                        });
                    }

                    listener.handlePieceAvailability(this, piece);
                    run(); // We might now be interested.
                    break;
                }

                case BITFIELD: {
                    // Augment the hasPiece bit field from this BITFIELD message
                    PeerMessage.BitfieldMessage message = (PeerMessage.BitfieldMessage) msg;
                    BitSet prevAvailablePieces;

                    synchronized (lock) {
                        prevAvailablePieces = getAvailablePieces();
                        this.availablePieces.clear();
                        this.availablePieces.or(message.getBitfield());
                        logger.trace("Recorded message from {} with {} "
                                + "pieces(s) [{}/{}].",
                                new Object[]{
                            this,
                            message.getBitfield().cardinality(),
                            getAvailablePieceCount(),
                            this.torrent.getPieceCount()
                        });
                    }

                    // The copy from the message is independent, and thus threadsafe.
                    listener.handleBitfieldAvailability(this, prevAvailablePieces, message.getBitfield());
                    run();  // We might now be interested.
                    break;
                }

                case REQUEST: {
                    PeerMessage.RequestMessage message = (PeerMessage.RequestMessage) msg;
                    Piece piece = this.torrent.getPiece(message.getPiece());

                    // If we are choking from this peer and it still sends us
                    // requests, it is a violation of the BitTorrent protocol.
                    // Similarly, if the peer requests a piece we don't have, it
                    // is a violation of the BitTorrent protocol. In these
                    // situation, terminate the connection.
                    if (isChoked()) {
                        // TODO: This isn't synchronous. We need to remember WHEN we choked them.
                        logger.warn("Peer {} ignored choking, "
                                + "terminating exchange.", this);
                        close();
                        break;
                    }

                    if (!piece.isValid()) {
                        logger.warn("Peer {} requested invalid piece {}, "
                                + "terminating exchange.", this, piece);
                        close();
                        break;
                    }

                    if (message.getLength() > PieceBlock.MAX_SIZE) {
                        logger.warn("Peer {} requested a block too big, "
                                + "terminating exchange.", this);
                        close();
                        break;
                    }

                    // At this point we agree to send the requested piece block to
                    // the remote peer, so let's queue a message with that block
                    ByteBuffer block = piece.read(message.getOffset(), message.getLength());
                    this.send(new PeerMessage.PieceMessage(
                            message.getPiece(),
                            message.getOffset(),
                            block));
                    this.upload.update(block.remaining());

                    listener.handleBlockSent(this, piece, message.getOffset(), message.getLength());

                    break;
                }

                case PIECE: {
                    // Record the incoming piece block.

                    // Should we keep track of the requested pieces and act when we
                    // get a piece we didn't ask for, or should we just stay
                    // greedy?
                    PeerMessage.PieceMessage message = (PeerMessage.PieceMessage) msg;
                    Piece piece = this.torrent.getPiece(message.getPiece());

                    // Remove the corresponding request from the request queue to
                    // make room for next block requests.
                    this.removeBlockRequest(message);
                    this.download.update(message.getBlock().remaining());
                    listener.handleBlockReceived(this, piece, message.getOffset(), message.getBlock().remaining());

                    DownloadingPiece sp = null;
                    boolean valid = sp.receive(message.getBlock(), message.getOffset());

                    if (valid)
                        listener.handlePieceCompleted(this, piece);
                    else
                        logger.warn("Downloaded piece#{} from {} was not valid ;-(",
                                piece.getIndex(), peer);
                    run();
                    break;
                }

                case CANCEL:
                    // No need to support
                    break;
            }
        } catch (IOException ioe) {
            listener.handleIOException(this, ioe);
        }
    }

    @Override
    public String toString() {
        return new StringBuilder(peer.toString())
                .append(" [")
                .append((isChoking() ? "C" : "c"))
                .append((isInterested() ? "I" : "i"))
                .append("|")
                .append((isChoked() ? "C" : "c"))
                .append((isInteresting() ? "I" : "i"))
                .append("|")
                .append(getAvailablePieceCount())
                .append("]")
                .toString();
    }
}
