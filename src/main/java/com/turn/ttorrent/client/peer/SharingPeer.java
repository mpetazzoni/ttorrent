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
import java.util.concurrent.atomic.AtomicIntegerArray;
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
    private static final int MAX_REQUESTS_SENT = 50;
    private static final int MAX_REQUESTS_RCVD = 100;

    private static enum Flag {
        // We decide about them:

        CHOKED, INTERESTING,
        // They decide about us:
        CHOKING, INTERESTED;
    }
    private final SharedTorrent torrent;
    private final Peer peer;
    private final PeerActivityListener listener;    // torrent.getPeerHandler()
    @GuardedBy("lock")
    private final BitSet availablePieces;
    // TODO: Convert to AtomicLongArray and allow some hysteresis on flag changes.
    private final AtomicIntegerArray flags = new AtomicIntegerArray(4);
    // @GuardedBy("requestsLock")
    // private final BlockingQueue<PeerMessage.RequestMessage> requests = new ArrayBlockingQueue<PeerMessage.RequestMessage>(SharingPeer.MAX_REQUESTS_SENT);
    private final EWMA download = new EWMA(60);
    private final EWMA upload = new EWMA(60);
    private final Object lock = new Object();
    @GuardedBy("lock")
    private SocketChannel channel;
    @Nonnull
    @GuardedBy("lock")
    private DownloadingPiece requestsSource;
    @GuardedBy("lock")
    private final Queue<DownloadingPiece.AnswerableRequestMessage> requestsSent = new LinkedList<DownloadingPiece.AnswerableRequestMessage>();
    @GuardedBy("lock")
    private int requestsSentLimit = MAX_REQUESTS_SENT;
    @GuardedBy("lock")
    private final Queue<PeerMessage.RequestMessage> requestsReceived = new LinkedList<PeerMessage.RequestMessage>();

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

        reset();
    }

    /**
     * Reset the peer state.
     *
     * <p>
     * Initially, peers are considered choked, choking, and neither interested
     * nor interesting.
     * </p>
     */
    public void reset() {
        setFlag(Flag.CHOKING, true);
        setFlag(Flag.INTERESTING, false);
        setFlag(Flag.CHOKED, true);
        setFlag(Flag.INTERESTED, false);

        synchronized (lock) {
            this.requestsSource = null;
            this.requestsSent.clear();
            this.requestsReceived.clear();
        }
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

    @Nonnull
    public EWMA getDLRate() {
        return download;
    }

    @Nonnull
    public EWMA getULRate() {
        return upload;
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

    private boolean getFlag(@Nonnull Flag flag) {
        return flags.get(flag.ordinal()) != 0;
    }

    private boolean setFlag(@Nonnull Flag flag, boolean value) {
        int curr = value ? 1 : 0;
        int prev = flags.getAndAdd(flag.ordinal(), curr);
        return prev != curr;
        // return flags.compareAndSet(flag.ordinal(), value ? 0 : 1, value ? 1 : 0);
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
        if (setFlag(Flag.CHOKED, true)) {
            logger.trace("Choking {}", this);
            this.send(new PeerMessage.ChokeMessage());
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
        if (setFlag(Flag.CHOKED, false)) {
            logger.trace("Unchoking {}", this);
            this.send(new PeerMessage.UnchokeMessage());
        }
    }

    public boolean isChoked() {
        return getFlag(Flag.CHOKED);
    }

    public void interesting() {
        if (setFlag(Flag.INTERESTING, true)) {
            logger.trace("Telling {} we're interested.", this);
            this.send(new PeerMessage.InterestedMessage());
        }
    }

    public void notInteresting() {
        if (setFlag(Flag.INTERESTING, false)) {
            logger.trace("Telling {} we're no longer interested.", this);
            this.send(new PeerMessage.NotInterestedMessage());
        }
    }

    public boolean isInteresting() {
        return getFlag(Flag.INTERESTING);
    }

    public boolean isChoking() {
        return getFlag(Flag.CHOKING);
    }

    public boolean isInterested() {
        return getFlag(Flag.INTERESTED);
    }

    @CheckForNull
    public SocketChannel getChannel() {
        synchronized (lock) {
            return channel;
        }
    }

    public void setChannel(@CheckForNull SocketChannel channel) {
        synchronized (lock) {
            if (this.channel != null)
                throw new IllegalStateException("Already connected.");
            this.channel = channel;
        }
    }

    public boolean isConnected() {
        return getChannel() != null;
    }

    public void close() {
        synchronized (lock) {
            if (channel != null)
                channel.close();
            channel = null;
        }
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
        SocketChannel c = getChannel();
        if (c != null) {
            c.write(message);
        } else {
            logger.warn("Attempting to send a message to non-connected peer {}! ({})", this, message);
        }
    }

    @GuardedBy("lock")
    private static <T extends PeerMessage.RequestMessage> T removeRequestMessage(
            @Nonnull PeerMessage.AbstractPieceMessage response,
            @Nonnull Iterator<T> requests) {
        T out = null;
        while (requests.hasNext()) {
            T request = requests.next();
            if (response.answers(request)) {
                out = request;
                requests.remove();
            }
        }
        return out;
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
    private DownloadingPiece.AnswerableRequestMessage removeRequestSent(@Nonnull PeerMessage.PieceMessage response) {
        synchronized (lock) {
            return removeRequestMessage(response, requestsSent.iterator());
        }
    }

    private void removeRequestReceived(@Nonnull PeerMessage.CancelMessage request) {
        synchronized (lock) {
            removeRequestMessage(request, requestsReceived.iterator());
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
    public int cancelRequestsSent() {
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
    // TODO: Do we want to make sure only one person enters this FSM at a time?
    private void run() throws IOException {
        SocketChannel c;
        // This locking could be more fine-grained.
        synchronized (lock) {
            c = getChannel();   // Share the reentrant lock.
            if (c == null)
                return;

            EXPIRE:
            {
                long then = System.currentTimeMillis() - 30000;
                Iterator<DownloadingPiece.AnswerableRequestMessage> it = requestsSent.iterator();
                while (it.hasNext()) {
                    DownloadingPiece.AnswerableRequestMessage requestSent = it.next();
                    if (requestSent.getRequestTime() < then) {
                        logger.warn("Peer {} request {} timed out.", this, requestSent);
                        it.remove();
                    }
                }
            }

            REQUEST:
            while (requestsSent.size() < requestsSentLimit) {
                if (!c.isWritable())
                    return;
                // Search for a block we can request. Ideally, this iterates once.
                DownloadingPiece.AnswerableRequestMessage request = null;
                while (request == null) {
                    // This calls a significant piece of infrastructure elsewhere,
                    // and needs a proof against deadlock.
                    if (requestsSource == null)
                        requestsSource = torrent.getPeerHandler().getNextPieceToDownload(this);
                    if (requestsSource == null) {
                        notInteresting();
                        break REQUEST;
                    }
                    request = requestsSource.nextRequest();
                }
                interesting();
                requestsSent.add(request);
                send(request);
            }
        }

        // This loop does I/O so we shouldn't hold the lock fully outside it.
        while (c.isWritable()) {
            PeerMessage.RequestMessage request;
            synchronized (lock) {
                request = requestsReceived.poll();
                if (request == null)
                    break;
            }

            Piece piece = torrent.getPiece(request.getPiece());
            if (!piece.isValid()) {
                logger.warn("Peer {} requested invalid piece {}, "
                        + "terminating exchange.", this, piece);
                close();
                break;
            }

            // At this point we agree to send the requested piece block to
            // the remote peer, so let's queue a message with that block
            ByteBuffer block = piece.read(request.getOffset(), request.getLength());
            send(new PeerMessage.PieceMessage(
                    request.getPiece(),
                    request.getOffset(),
                    block));
            upload.update(request.getLength());

            listener.handleBlockSent(this, piece, request.getOffset(), request.getLength());
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
                    setFlag(Flag.CHOKING, true);
                    logger.trace("Peer {} is no longer accepting requests.", this);
                    this.cancelRequestsSent();
                    listener.handlePeerChoking(this);
                    break;

                case UNCHOKE:
                    setFlag(Flag.CHOKING, false);
                    logger.trace("Peer {} is now accepting requests.", this);
                    listener.handlePeerUnchoking(this);
                    run();  // We might want something.
                    break;

                case INTERESTED:
                    setFlag(Flag.INTERESTED, true);
                    logger.trace("Peer {} is now interested.", this);
                    break;

                case NOT_INTERESTED:
                    setFlag(Flag.INTERESTED, false);
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

                    if (message.getLength() > PieceBlock.MAX_SIZE) {
                        logger.warn("Peer {} requested a block too big, "
                                + "terminating exchange.", this);
                        close();
                        break;
                    }

                    synchronized (lock) {
                        if (requestsReceived.size() > MAX_REQUESTS_RCVD) {
                            logger.warn("Peer {} requested too many blocks; dropping {}",
                                    this, message);
                            break;
                        }

                        requestsReceived.add(message);
                        run();
                    }

                    break;
                }

                case PIECE: {
                    // Record the incoming piece block.

                    // Should we keep track of the requested pieces and act when we
                    // get a piece we didn't ask for, or should we just stay
                    // greedy?
                    PeerMessage.PieceMessage message = (PeerMessage.PieceMessage) msg;
                    int blockLength = message.getLength();
                    Piece piece = this.torrent.getPiece(message.getPiece());

                    // Remove the corresponding request from the request queue to
                    // make room for next block requests.
                    DownloadingPiece.AnswerableRequestMessage request = removeRequestSent(message);
                    DownloadingPiece.Reception reception = request.answer(message);

                    download.update(blockLength);
                    listener.handleBlockReceived(this, piece, message.getOffset(), blockLength);
                    switch (reception) {
                        case VALID:
                        case INVALID:
                            listener.handlePieceCompleted(this, piece);
                            break;
                    }

                    run();
                    break;
                }

                case CANCEL: {
                    PeerMessage.CancelMessage message = (PeerMessage.CancelMessage) msg;
                    removeRequestReceived(message);
                    break;
                }
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
