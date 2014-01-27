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

import com.turn.ttorrent.client.PeerPieceProvider;
import com.turn.ttorrent.client.Piece;

import com.turn.ttorrent.client.io.PeerMessage;
import com.turn.ttorrent.common.Torrent;
import io.netty.channel.Channel;
import java.io.IOException;
import java.net.SocketAddress;
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
public class PeerHandler implements PeerMessageListener {

    private static final Logger LOG = LoggerFactory.getLogger(PeerHandler.class);
    private static final int MAX_REQUESTS_SENT = 1;
    private static final int MAX_REQUESTS_RCVD = 100;

    private static enum Flag {
        // We decide about them:

        CHOKED, INTERESTING,
        // They decide about us:
        CHOKING, INTERESTED;
    }
    private final byte[] peerId;
    private final Channel channel;
    private final PeerPieceProvider provider;
    private final PeerActivityListener listener;
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
    private boolean bitfieldSent = false;
    @GuardedBy("lock")
    private long keepaliveSent;
    @Nonnull
    @GuardedBy("lock")
    private PieceHandler requestsSource = null;
    @GuardedBy("lock")
    private final Queue<PieceHandler.AnswerableRequestMessage> requestsSent = new LinkedList<PieceHandler.AnswerableRequestMessage>();
    @GuardedBy("lock")
    private int requestsSentLimit = MAX_REQUESTS_SENT;
    @GuardedBy("lock")
    private final Queue<PeerMessage.RequestMessage> requestsReceived = new LinkedList<PeerMessage.RequestMessage>();

    /**
     * Create a new sharing peer on a given torrent.
     *
     * <p>
     * Initially, peers are considered choked, choking, and neither interested
     * nor interesting.
     * </p>
     *
     * @param ip The peer's IP address.
     * @param port The peer's port.
     * @param peer The peer.
     * @param torrent The torrent this peer exchanges with us on.
     */
    public PeerHandler(
            @Nonnull byte[] peerId,
            @Nonnull Channel channel,
            // Deliberately specified in terms of interfaces, for testing.
            @Nonnull PeerPieceProvider provider,
            @Nonnull PeerActivityListener listener) {
        this.peerId = peerId;
        this.channel = channel;
        this.provider = provider;
        this.listener = listener;

        this.availablePieces = new BitSet(provider.getPieceCount());

        setFlag(Flag.CHOKING, true);
        setFlag(Flag.INTERESTING, false);
        setFlag(Flag.CHOKED, true);
        setFlag(Flag.INTERESTED, false);
    }

    @Nonnull
    public byte[] getPeerId() {
        return peerId;
    }

    @Nonnull
    public String getHexPeerId() {
        return Torrent.byteArrayToHexString(getPeerId());
    }

    @Nonnull
    public String getTextPeerId() {
        return Torrent.byteArrayToText(getPeerId());
    }

    @Nonnull
    public SocketAddress getRemoteAddress() {
        return channel.remoteAddress();
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
        int prev = flags.getAndSet(flag.ordinal(), curr);
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
            LOG.trace("Choking {}", this);
            send(new PeerMessage.ChokeMessage(), true);
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
            LOG.trace("Unchoking {}", this);
            send(new PeerMessage.UnchokeMessage(), true);
        }
    }

    public boolean isChoked() {
        return getFlag(Flag.CHOKED);
    }

    public void interesting() {
        if (setFlag(Flag.INTERESTING, true)) {
            LOG.trace("Telling {} we're interested.", this);
            send(new PeerMessage.InterestedMessage(), true);
        }
    }

    public void notInteresting() {
        if (setFlag(Flag.INTERESTING, false)) {
            LOG.trace("Telling {} we're no longer interested.", this);
            send(new PeerMessage.NotInterestedMessage(), true);
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

    public void close() {
        channel.close();
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
    public void send(@Nonnull PeerMessage message, boolean flush) throws IllegalStateException {
        if (flush)
            channel.writeAndFlush(message);
        else
            channel.write(message);
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
    @CheckForNull
    private PieceHandler.AnswerableRequestMessage removeRequestSent(@Nonnull PeerMessage.PieceMessage response) {
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
        // Set<PieceHandler> pieces = new HashSet<PieceHandler>();
        synchronized (lock) {
            int count = 0;
            for (PeerMessage.RequestMessage request : requestsSent) {
                send(new PeerMessage.CancelMessage(request), false);
                count++;
            }
            channel.flush();
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
    public void run() throws IOException {
        // LOG.trace("Step function in " + this);
        Channel c = channel;
        boolean flush = false;
        try {
            // This locking could be more fine-grained.
            synchronized (lock) {

                BITFIELD:
                {
                    if (!c.isWritable()) {
                        LOG.debug("Peer {} channel {} not writable for bitfield.", this, c);
                        return;
                    }
                    if (!bitfieldSent) {
                        flush = true;
                        send(new PeerMessage.BitfieldMessage(provider.getCompletedPieces()), false);
                        bitfieldSent = true;
                    }
                }

                EXPIRE:
                {
                    long then = System.currentTimeMillis() - 30000;
                    Iterator<PieceHandler.AnswerableRequestMessage> it = requestsSent.iterator();
                    while (it.hasNext()) {
                        PieceHandler.AnswerableRequestMessage requestSent = it.next();
                        if (requestSent.getRequestTime() < then) {
                            LOG.warn("Peer {} request {} timed out.", this, requestSent);
                            it.remove();
                        }
                    }
                }

                INTERESTING:
                {
                    BitSet interesting = getAvailablePieces();
                    provider.andNotCompletedPieces(interesting);
                    if (interesting.isEmpty())
                        notInteresting();
                    else
                        interesting();
                }

                REQUEST:
                if (!isChoking()) {
                    while (requestsSent.size() < requestsSentLimit) {
                        if (!c.isWritable()) {
                            LOG.debug("Peer {} channel {} not writable for request.", this, c);
                            return;
                        }
                        // Search for a block we can request. Ideally, this iterates once.
                        PieceHandler.AnswerableRequestMessage request = null;
                        while (request == null) {
                            // This calls a significant piece of infrastructure elsewhere,
                            // and needs a proof against deadlock.
                            if (requestsSource == null)
                                requestsSource = provider.getNextPieceToDownload(this);
                            // LOG.debug("RequestSource is {}", requestsSource);
                            if (requestsSource == null) {
                                LOG.debug("Peer {} has no request source; breaking request loop.", this);
                                notInteresting();
                                break REQUEST;
                            }
                            request = requestsSource.nextRequest();
                            // LOG.debug("Request is {}", request);
                            if (request == null)
                                requestsSource = null;
                        }
                        interesting();
                        requestsSent.add(request);
                        // TODO: findbugs thinks this can be null, but I don't.
                        flush = true;
                        send(request, false);
                    }
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

                Piece piece = provider.getPiece(request.getPiece());
                if (!piece.isValid()) {
                    LOG.warn("Peer {} requested invalid piece {}, "
                            + "terminating exchange.", this, piece);
                    close();
                    break;
                }

                // At this point we agree to send the requested piece block to
                // the remote peer, so let's queue a message with that block
                ByteBuffer block = ByteBuffer.allocate(request.getLength());
                provider.readBlock(block, request.getPiece(), request.getOffset());
                block.flip();
                // ByteBuffer block = piece.read(request.getOffset(), request.getLength());
                flush = true;
                send(new PeerMessage.PieceMessage(
                        request.getPiece(),
                        request.getOffset(),
                        block), false);
                upload.update(request.getLength());

                listener.handleBlockSent(this, request.getPiece(), request.getOffset(), request.getLength());
            }
        } finally {
            if (flush)
                channel.flush();
        }
    }

    /**
     * Handle an incoming message from this peer.
     *
     * @param msg The incoming, parsed message.
     */
    @Override
    public void handleMessage(PeerMessage msg) throws IOException {
        switch (msg.getType()) {
            case KEEP_ALIVE:
                // Nothing to do, we're keeping the connection open anyways.
                break;

            case CHOKE:
                setFlag(Flag.CHOKING, true);
                LOG.trace("Peer {} is no longer accepting requests.", this);
                cancelRequestsSent();
                listener.handlePeerChoking(this);
                break;

            case UNCHOKE:
                setFlag(Flag.CHOKING, false);
                LOG.trace("Peer {} is now accepting requests.", this);
                listener.handlePeerUnchoking(this);
                run();  // We might want something.
                break;

            case INTERESTED:
                setFlag(Flag.INTERESTED, true);
                LOG.trace("Peer {} is now interested.", this);
                break;

            case NOT_INTERESTED:
                setFlag(Flag.INTERESTED, false);
                LOG.trace("Peer {} is no longer interested.", this);
                break;

            case HAVE: {
                // Record this peer has the given piece
                PeerMessage.HaveMessage message = (PeerMessage.HaveMessage) msg;

                synchronized (lock) {
                    this.availablePieces.set(message.getPiece());
                }

                listener.handlePieceAvailability(this, message.getPiece());
                run(); // We might now be interested.
                break;
            }

            case BITFIELD: {
                // Augment the hasPiece bit field from this BITFIELD message
                PeerMessage.BitfieldMessage message = (PeerMessage.BitfieldMessage) msg;
                BitSet prevAvailablePieces;

                synchronized (lock) {
                    prevAvailablePieces = getAvailablePieces();
                    availablePieces.clear();
                    availablePieces.or(message.getBitfield());
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
                    LOG.warn("Peer {} ignored choking, "
                            + "terminating exchange.", this);
                    close();
                    break;
                }

                // TODO: Ignore this condition for fast links.
                if (message.getLength() > PieceHandler.MAX_BLOCK_SIZE) {
                    LOG.warn("Peer {} requested a block too big, "
                            + "terminating exchange.", this);
                    close();
                    break;
                }

                synchronized (lock) {
                    if (requestsReceived.size() > MAX_REQUESTS_RCVD) {
                        LOG.warn("Peer {} requested too many blocks; dropping {}",
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

                // Remove the corresponding request from the request queue to
                // make room for next block requests.
                PieceHandler.AnswerableRequestMessage request = removeRequestSent(message);
                PieceHandler.Reception reception = PieceHandler.Reception.WAT;
                if (request != null)
                    reception = request.answer(message);
                else
                    LOG.warn("Response received to unsent request: {}", message);

                download.update(blockLength);
                listener.handleBlockReceived(this, message.getPiece(), message.getOffset(), blockLength);
                switch (reception) {
                    case VALID:
                    case INVALID:
                        listener.handlePieceCompleted(this, message.getPiece());
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
    }

    @Override
    public void handleWritable() throws IOException {
        run();
    }

    @Override
    public String toString() {
        // Channel c = getChannel();
        return new StringBuilder(getTextPeerId())
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
