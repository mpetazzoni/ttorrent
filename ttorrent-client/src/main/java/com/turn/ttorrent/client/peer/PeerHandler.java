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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Iterators;
import com.turn.ttorrent.client.PeerPieceProvider;
import com.turn.ttorrent.client.io.PeerExtendedMessage;
import com.turn.ttorrent.client.io.PeerHandshakeMessage;
import com.turn.ttorrent.client.io.PeerMessage;
import com.turn.ttorrent.common.SuppressWarnings;
import com.turn.ttorrent.common.TorrentUtils;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLongArray;
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
    public static final int MAX_REQUESTS_SENT = 100;
    public static final int MIN_REQUESTS_SENT = 16;
    public static final int MAX_REQUESTS_RCVD = 100;
    public static final long MAX_REQUESTS_TIME = TimeUnit.SECONDS.toMillis(32);
    public static final long MIN_PEX_DELAY = TimeUnit.SECONDS.toMillis(72); // Protocol requires minimum 60.
    private static final Map<PeerExtendedMessage.ExtendedType, Byte> DEFAULT_EXTENDED_MESSAGE_TYPE_MAP = Collections.singletonMap(PeerExtendedMessage.ExtendedType.handshake, (byte) 0);
    private static final ChannelFutureListener CHANNEL_FUTURE_LISTENER = new ChannelFutureListener() {
        @Override
        public void operationComplete(ChannelFuture future) throws Exception {
            if (!future.channel().isOpen()) // Then everything fails.
                return;
            if (future.channel().closeFuture().isDone())
                return;
            try {
                future.get();
            } catch (Exception e) {
                LOG.error("Operation failed: " + e, e);
            }
        }
    };

    private static enum Flag {
        // We decide about them:

        CHOKED, INTERESTING,
        // They decide about us:
        CHOKING, INTERESTED;
    }
    private final Channel channel;
    private final byte[] remotePeerId;
    private final byte[] remoteReserved;
    private final PeerPieceProvider provider;
    private final PeerExistenceListener existenceListener;
    private final PeerConnectionListener connectionListener;
    private final PeerActivityListener activityListener;
    @GuardedBy("lock")
    private final BitSet availablePieces;
    @GuardedBy("lock")
    private Map<PeerExtendedMessage.ExtendedType, Byte> extendedMessageTypes = DEFAULT_EXTENDED_MESSAGE_TYPE_MAP;
    // TODO: Convert to AtomicLongArray and allow some hysteresis on flag changes.
    private final AtomicLongArray flags = new AtomicLongArray(4);
    // @GuardedBy("requestsLock")
    // private final BlockingQueue<PeerMessage.RequestMessage> requests = new ArrayBlockingQueue<PeerMessage.RequestMessage>(SharingPeer.MAX_REQUESTS_SENT);
    private final Rate download = new Rate(60);
    private final Rate upload = new Rate(60);
    private final Object lock = new Object();

    private static enum SendState {

        BITFIELD, EXTENDED_HANDSHAKE;
    }
    @GuardedBy("lock")
    private Set<SendState> sent = EnumSet.noneOf(SendState.class);
    @Nonnull
    @GuardedBy("lock")
    private Iterator<PieceHandler.AnswerableRequestMessage> requestsSource = Iterators.emptyIterator();
    // @GuardedBy("lock")   // It's now a concurrent structure.
    // The limit should be irrelevant, it's just to protect us.
    private final BlockingQueue<PieceHandler.AnswerableRequestMessage> requestsSent = new LinkedBlockingQueue<PieceHandler.AnswerableRequestMessage>(MAX_REQUESTS_SENT * 2);
    @GuardedBy("lock")
    private int requestsSentLimit = MAX_REQUESTS_SENT;
    @GuardedBy("lock")
    private long requestsExpiredAt = 0;
    // @GuardedBy("lock")   // Also now a concurrent structure.
    private final BlockingQueue<PeerMessage.RequestMessage> requestsReceived = new ArrayBlockingQueue<PeerMessage.RequestMessage>(MAX_REQUESTS_RCVD);
    @GuardedBy("lock")
    private final Set<SocketAddress> peersExchanged = new HashSet<SocketAddress>();
    @GuardedBy("lock")
    private long peersExchangedAt = 0;

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
    @SuppressWarnings("EI_EXPOSE_REP2")
    public PeerHandler(
            @Nonnull Channel channel,
            @Nonnull byte[] remotePeerId,
            @Nonnull byte[] remoteReserved,
            // Deliberately specified in terms of interfaces, for testing.
            @Nonnull PeerPieceProvider provider,
            @Nonnull PeerExistenceListener existenceListener,
            @Nonnull PeerConnectionListener connectionListener,
            @Nonnull PeerActivityListener activityListener) {
        this.channel = channel;
        this.remotePeerId = remotePeerId;
        this.remoteReserved = remoteReserved;
        this.provider = provider;
        this.existenceListener = existenceListener;
        this.connectionListener = connectionListener;
        this.activityListener = activityListener;

        this.availablePieces = new BitSet(provider.getPieceCount());

        setFlag(Flag.CHOKING, true);
        setFlag(Flag.INTERESTING, false);
        setFlag(Flag.CHOKED, true);
        setFlag(Flag.INTERESTED, false);
    }

    @Nonnull
    @SuppressWarnings("EI_EXPOSE_REP")
    public byte[] getRemotePeerId() {
        return remotePeerId;
    }

    @Nonnull
    public String getHexRemotePeerId() {
        return TorrentUtils.toHex(getRemotePeerId());
    }

    @Nonnull
    private String getTextRemotePeerId() {
        return TorrentUtils.toText(getRemotePeerId());
    }

    @Nonnull
    public SocketAddress getLocalAddress() {
        return channel.localAddress();
    }

    @Nonnull
    public SocketAddress getRemoteAddress() {
        return channel.remoteAddress();
    }

    @Nonnull
    public Rate getDLRate() {
        return download;
    }

    @Nonnull
    public Rate getULRate() {
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

    @Override
    public Map<? extends PeerExtendedMessage.ExtendedType, ? extends Byte> getExtendedMessageTypes() {
        synchronized (lock) {
            return extendedMessageTypes;
        }
    }

    private boolean isExtendedTypeSupported(@Nonnull PeerExtendedMessage.ExtendedType extendedType) {
        if (extendedType == PeerExtendedMessage.ExtendedType.handshake)
            return PeerHandshakeMessage.Feature.BEP10_EXTENSION_PROTOCOL.get(remoteReserved);
        synchronized (lock) {
            boolean ret = extendedMessageTypes.containsKey(extendedType);
            // LOG.info("{}: {} supports {} = {}", new Object[]{provider.getLocalPeerName(), getTextRemotePeerId(), extendedType, ret});
            return ret;
        }
    }

    @Nonnegative
    public int getAvailablePieceCount() {
        synchronized (lock) {
            return availablePieces.cardinality();
        }
    }

    /** @return true if this flag was set more than delta ms ago. */
    private boolean getFlag(@Nonnull Flag flag, @Nonnegative int delta) {
        // <= so that a fast set; get(0) is true.
        long curr = flags.get(flag.ordinal());
        long now = System.currentTimeMillis();
        boolean ret = curr != 0 && curr + delta <= now;
        // LOG.debug("{}: flag={}, curr={}, delta={}, now={}, ret={}", new Object[]{ provider.getLocalPeerName(), flag, curr, delta, now, ret });
        return ret;
    }

    private static boolean toBoolean(long value) {
        return value != 0;
    }

    /** @return true if the flag was changed, in a "boolean" sense. */
    private boolean setFlag(@Nonnull Flag flag, boolean value) {
        // Avoid updating the timestamp if we can.
        if (value == toBoolean(flags.get(flag.ordinal())))
            return false;
        long curr = value ? System.currentTimeMillis() : 0;
        long prev = flags.getAndSet(flag.ordinal(), curr);
        return value != toBoolean(prev);
        // return flags.compareAndSet(flag.ordinal(), value ? 0 : 1, value ? 1 : 0);
    }

    @Nonnegative
    public int getRequestsSentCount() {
        return requestsSent.size();
    }

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
            if (LOG.isTraceEnabled())
                LOG.trace("{}: Choking {}", provider.getLocalPeerName(), this);
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
            if (LOG.isTraceEnabled())
                LOG.trace("{}: Unchoking {}", provider.getLocalPeerName(), this);
            send(new PeerMessage.UnchokeMessage(), true);
            // LOG.info("{}: Unchoking {}", provider.getLocalPeerName(), this);
        }
    }

    public boolean isChoked(@Nonnegative int delta) {
        return getFlag(Flag.CHOKED, delta);
    }

    public void interesting() {
        if (setFlag(Flag.INTERESTING, true)) {
            if (LOG.isTraceEnabled())
                LOG.trace("{}: Telling {} we're interested.", provider.getLocalPeerName(), this);
            send(new PeerMessage.InterestedMessage(), true);
        }
    }

    public void notInteresting() {
        if (setFlag(Flag.INTERESTING, false)) {
            if (LOG.isTraceEnabled())
                LOG.trace("{}: Telling {} we're no longer interested.", provider.getLocalPeerName(), this);
            send(new PeerMessage.NotInterestedMessage(), true);
        }
    }

    public boolean isInteresting() {
        return getFlag(Flag.INTERESTING, 0);
    }

    public boolean isChoking() {
        return getFlag(Flag.CHOKING, 0);
    }

    public boolean isInterested() {
        return getFlag(Flag.INTERESTED, 0);
    }

    public void close(@Nonnull String reason) {
        rejectRequestsSent("connection closed: " + reason);
        LOG.debug("{}: Closing {}: {}", new Object[]{
            provider.getLocalPeerName(),
            this, reason
        });
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
        if (message instanceof PeerExtendedMessage) {
            PeerExtendedMessage.ExtendedType extendedType = ((PeerExtendedMessage) message).getExtendedType();
            if (!isExtendedTypeSupported(extendedType)) {
                LOG.warn("Extended message type not supported by remote end: " + message);
                return;
            }
        }
        // LOG.info("{}: -> {}", new Object[]{provider.getLocalPeerName(), message});
        ChannelFuture f;
        if (flush)
            f = channel.writeAndFlush(message);
        else
            f = channel.write(message);
        f.addListener(CHANNEL_FUTURE_LISTENER);
    }

    @GuardedBy("lock")
    private static <T extends PeerMessage.RequestMessage> T removeRequestMessage(
            @Nonnull PeerMessage.AbstractPieceMessage response,
            @Nonnull Iterator<T> requests) {
        // int count = 0;
        T out = null;
        while (requests.hasNext()) {
            T request = requests.next();
            if (response.answers(request)) {
                out = request;
                requests.remove();
                // count++;
            }
        }
        // if (count > 1) LOG.error("Removed multiple requests for " + response, new Exception());
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
        return removeRequestMessage(response, requestsSent.iterator());
    }

    private void removeRequestReceived(@Nonnull PeerMessage.CancelMessage request) {
        removeRequestMessage(request, requestsReceived.iterator());
    }

    private void rejectRequests(@Nonnull Collection<? extends PieceHandler.AnswerableRequestMessage> requests, @Nonnull String reason) {
        if (!requests.isEmpty()) {
            int count = provider.addRequestTimeout(requests);
            if (count > 0)
                if (LOG.isInfoEnabled())
                    LOG.info("{}: Rejecting {} requests; {} accepted: {}", provider.getLocalPeerName(), requests.size(), count, reason);
        }
    }

    public void rejectRequestsSent(@Nonnull String reason) {
        List<PieceHandler.AnswerableRequestMessage> requestsRejected = new ArrayList<PieceHandler.AnswerableRequestMessage>();
        requestsSent.drainTo(requestsRejected);
        rejectRequests(requestsRejected, reason);
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
    public int cancelRequestsSent(@Nonnull String reason) {
        // Set<PieceHandler> pieces = new HashSet<PieceHandler>();
        List<PieceHandler.AnswerableRequestMessage> requestsRejected = new ArrayList<PieceHandler.AnswerableRequestMessage>();
        requestsSent.drainTo(requestsRejected);
        for (PieceHandler.AnswerableRequestMessage requestRejected : requestsRejected)
            send(new PeerMessage.CancelMessage(requestRejected), false);
        rejectRequests(requestsRejected, reason);
        if (!requestsRejected.isEmpty())
            channel.flush();
        if (LOG.isTraceEnabled())
            LOG.trace("{}: Cancelled {} remaining pending requests on {}.", new Object[]{
                provider.getLocalPeerName(),
                requestsRejected.size(), this
            });
        return requestsRejected.size();
    }

    private boolean isWritable(@Nonnull Channel c, @Nonnull String message) {
        if (c.isWritable())
            return true;
        LOG.debug("{}: Peer {} channel {} not writable for {}.", new Object[]{
            provider.getLocalPeerName(),
            this, c, message
        });
        return false;
    }

    /**
     * Run one step of the PeerHandler finite state machine.
     *
     * <p>
     * Re-fill the pipeline to get download the next blocks from the peer.
     * </p>
     */
    public void run(String reason) throws IOException {
        if (LOG.isTraceEnabled())
            LOG.trace("{}: Step function in {}: {}", new Object[]{
                provider.getLocalPeerName(), this, reason
            });
        Channel c = channel;
        boolean flush = false;
        try {
            // This locking could be more fine-grained.
            synchronized (lock) {

                BITFIELD:
                {
                    if (!sent.contains(SendState.BITFIELD)) {
                        if (!isWritable(c, "bitfield"))
                            return;
                        flush = true;
                        send(new PeerMessage.BitfieldMessage(provider.getCompletedPieces()), false);
                        sent.add(SendState.BITFIELD);
                    }
                }

                EXTENDED_HANDSHAKE:
                {
                    if (!isExtendedTypeSupported(PeerExtendedMessage.ExtendedType.handshake))
                        break EXTENDED_HANDSHAKE;
                    if (!sent.contains(SendState.EXTENDED_HANDSHAKE)) {
                        if (!isWritable(c, "extended handshake"))
                            return;
                        flush = true;
                        PeerExtendedMessage.HandshakeMessage message = new PeerExtendedMessage.HandshakeMessage(MAX_REQUESTS_RCVD);
                        send(message, false);
                        sent.add(SendState.EXTENDED_HANDSHAKE);
                    }
                }

                long now = System.currentTimeMillis();

                PEX:
                {
                    if (!isExtendedTypeSupported(PeerExtendedMessage.ExtendedType.ut_pex))
                        break PEX;
                    // LOG.info("{}: {} PEX supported.", new Object[]{provider.getLocalPeerName(), getTextRemotePeerId()});
                    if (peersExchangedAt > now - MIN_PEX_DELAY)
                        break PEX;
                    List<SocketAddress> peers = new ArrayList<SocketAddress>(existenceListener.getPeers());
                    // LOG.info("{}: {} PEX candidates are {}", new Object[]{provider.getLocalPeerName(), getTextRemotePeerId(), peers});
                    peers.removeAll(peersExchanged);
                    peers.remove(getRemoteAddress());
                    // LOG.info("{}: {} PEX candidates are now {}", new Object[]{provider.getLocalPeerName(), getTextRemotePeerId(), peers});
                    if (peers.isEmpty())
                        break PEX;
                    peers = peers.subList(0, Math.min(peers.size(), 100));
                    // LOG.info("{}: {} PEX {}", new Object[]{provider.getLocalPeerName(), getTextRemotePeerId(), peers});
                    peersExchanged.addAll(peers);
                    peersExchangedAt = now;
                    flush = true;
                    send(new PeerExtendedMessage.UtPexMessage(peers, Collections.<SocketAddress>emptyList()), false);
                }

                BitSet interesting = getAvailablePieces();
                provider.andNotCompletedPieces(interesting);
                INTERESTING:
                {
                    if (interesting.isEmpty())
                        notInteresting();
                    else
                        interesting();
                }

                // Expires dead requests, and marks live ones uninteresting.
                EXPIRE:
                {
                    if (LOG.isTraceEnabled())
                        LOG.trace("{}: requestsExpiredAt={}, now={}, comp={}", new Object[]{
                            provider.getLocalPeerName(),
                            requestsExpiredAt, now, now - (MAX_REQUESTS_TIME >> 2)
                        });
                    if (requestsExpiredAt < now - (MAX_REQUESTS_TIME >> 2)) {
                        long then = now - MAX_REQUESTS_TIME;
                        List<PieceHandler.AnswerableRequestMessage> requestsExpired = new ArrayList<PieceHandler.AnswerableRequestMessage>();
                        Iterator<PieceHandler.AnswerableRequestMessage> it = requestsSent.iterator();
                        while (it.hasNext()) {
                            PieceHandler.AnswerableRequestMessage requestSent = it.next();
                            if (requestSent.getRequestTime() < then) {
                                if (LOG.isTraceEnabled())
                                    LOG.trace("{}: Peer {} request {} timed out.", new Object[]{
                                        provider.getLocalPeerName(),
                                        this, requestSent
                                    });
                                requestsExpired.add(requestSent);
                                requestsSentLimit = Math.max((int) (requestsSentLimit * 0.8), MIN_REQUESTS_SENT);
                                it.remove();
                            } else {
                                interesting.clear(requestSent.getPiece());
                            }
                        }
                        rejectRequests(requestsExpired, "requests expired");
                        requestsExpiredAt = now;
                    }
                }

                // Makes new requests.
                REQUEST:
                {
                    while (requestsSent.size() < requestsSentLimit) {
                        // A choke message can come in while we are iterating.
                        if (isChoking()) {
                            if (LOG.isTraceEnabled())
                                LOG.trace("{}: {}: Not sending requests because they are choking us.", new Object[]{
                                    provider.getLocalPeerName(), this
                                });
                            break REQUEST;
                        }

                        if (!c.isWritable()) {
                            if (LOG.isDebugEnabled())
                                LOG.debug("{}: Peer {} channel {} not writable for request; sent {}.", new Object[]{
                                    provider.getLocalPeerName(),
                                    this, c,
                                    requestsSent.size()
                                });
                            return;
                        }

                        // Search for a block we can request. Ideally, this iterates 0 or 1 times.
                        while (!requestsSource.hasNext()) {
                            // This calls a significant piece of infrastructure elsewhere,
                            // and needs a proof against deadlock.
                            Iterable<PieceHandler.AnswerableRequestMessage> piece = provider.getNextPieceHandler(this, interesting);
                            if (piece == null) {
                                if (LOG.isTraceEnabled())
                                    LOG.trace("{}: Peer {} has no request source; breaking request loop.", new Object[]{
                                        provider.getLocalPeerName(),
                                        this
                                    });
                                requestsSource = Iterators.emptyIterator(); // Allow GC.
                                break REQUEST;
                            }
                            requestsSource = piece.iterator();
                        }

                        PieceHandler.AnswerableRequestMessage request = requestsSource.next();
                        if (LOG.isTraceEnabled())
                            LOG.trace("{}: Adding {} from {}, queue={}/{}", new Object[]{
                                provider.getLocalPeerName(),
                                request, requestsSource,
                                requestsSent.size(), requestsSentLimit
                            });
                        interesting.clear(request.getPiece());  // Don't pick up the same piece on the next iteration.
                        request.setRequestTime();
                        requestsSent.add(request);
                        flush = true;
                        send(request, false);
                    }
                }
            }

            // This loop does I/O so we shouldn't hold the lock fully outside it.
            RESPONSE:
            while (c.isWritable()) {
                PeerMessage.RequestMessage request = requestsReceived.poll();
                request = provider.getInstrumentation().instrumentBlockRequest(this, provider, request);
                if (request == null)
                    break;

                if (!provider.isCompletedPiece(request.getPiece())) {
                    LOG.warn("{}: Peer {} requested invalid piece {}, terminating exchange.", new Object[]{
                        provider.getLocalPeerName(),
                        this, request.getPiece()
                    });
                    close("requested piece we don't have");
                    break;
                }

                // At this point we agree to send the requested piece block to
                // the remote peer, so let's queue a message with that block
                ByteBuffer block = ByteBuffer.allocate(request.getLength());
                provider.readBlock(block, request.getPiece(), request.getOffset());
                block.flip();
                // ByteBuffer block = piece.read(request.getOffset(), request.getLength());
                PeerMessage.PieceMessage response = new PeerMessage.PieceMessage(
                        request.getPiece(),
                        request.getOffset(),
                        block);
                // response = provider.getInstrumentation().
                flush = true;
                send(response, false);
                upload.update(request.getLength());

                activityListener.handleBlockSent(this, request.getPiece(), request.getOffset(), request.getLength());
            }
        } finally {
            if (flush)
                channel.flush();
            if (LOG.isTraceEnabled())
                LOG.trace("After run: requestsSent={}", requestsSent);
        }
    }

    /**
     * Handle an incoming message from this peer.
     *
     * @param msg The incoming, parsed message.
     */
    @Override
    public void handleMessage(PeerMessage msg) throws IOException {
        // LOG.info("{}: <- {}", new Object[]{provider.getLocalPeerName(), msg});
        switch (msg.getType()) {
            case KEEP_ALIVE:
                // Nothing to do, we're keeping the connection open anyways.
                break;

            case CHOKE:
                setFlag(Flag.CHOKING, true);
                if (LOG.isTraceEnabled())
                    LOG.trace("{}: Peer {} is no longer accepting requests.", provider.getLocalPeerName(), this);
                cancelRequestsSent("remote peer choked us");
                activityListener.handlePeerChoking(this);
                break;

            case UNCHOKE:
                setFlag(Flag.CHOKING, false);
                if (LOG.isTraceEnabled())
                    LOG.trace("{}: Peer {} is now accepting requests.", provider.getLocalPeerName(), this);
                activityListener.handlePeerUnchoking(this);
                // run();  // We might want something.
                break;

            case INTERESTED:
                setFlag(Flag.INTERESTED, true);
                if (LOG.isTraceEnabled())
                    LOG.trace("{}: Peer {} is now interested.", provider.getLocalPeerName(), this);
                break;

            case NOT_INTERESTED:
                setFlag(Flag.INTERESTED, false);
                if (LOG.isTraceEnabled())
                    LOG.trace("{}: Peer {} is no longer interested.", provider.getLocalPeerName(), this);
                // TODO: Close if we are a seed?
                break;

            case HAVE: {
                // Record this peer has the given piece
                PeerMessage.HaveMessage message = (PeerMessage.HaveMessage) msg;

                synchronized (lock) {
                    availablePieces.set(message.getPiece());
                }

                activityListener.handlePieceAvailability(this, message.getPiece());
                // run(); // We might now be interested, but we should get it in handleReadComplete.
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
                activityListener.handleBitfieldAvailability(this, prevAvailablePieces, message.getBitfield());
                // run(); // We might now be interested, but we should get it in handleReadComplete.
                break;
            }

            case REQUEST: {
                PeerMessage.RequestMessage message = (PeerMessage.RequestMessage) msg;

                // If we are choking from this peer and it still sends us
                // requests, it is a violation of the BitTorrent protocol.
                // Similarly, if the peer requests a piece we don't have, it
                // is a violation of the BitTorrent protocol. In these
                // situation, terminate the connection.
                if (isChoked(2000)) {
                    // TODO: This isn't synchronous. We need to remember WHEN we choked them.
                    long choked = flags.get(Flag.CHOKED.ordinal());
                    long now = System.currentTimeMillis();
                    LOG.warn("{}: Peer {} ignored choking, terminating exchange; choked at {} ({} ago), now {}", new Object[]{
                        provider.getLocalPeerName(), this,
                        choked, (now - choked), now
                    });
                    close("ignored choking");
                    break;
                }

                // TODO: Ignore this condition for fast links.
                if (message.getLength() > PieceHandler.MAX_BLOCK_SIZE) {
                    LOG.warn("{}: Peer {} requested a block too big ({}), terminating exchange.", new Object[]{
                        provider.getLocalPeerName(), this,
                        message.getLength()
                    });
                    close("requested hueg block");
                    break;
                }

                if (!requestsReceived.offer(message)) {
                    LOG.warn("{}: Peer {} requested too many blocks; dropping {}", new Object[]{
                        provider.getLocalPeerName(),
                        this, message
                    });
                    break;
                }

                // run();
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
                else if (LOG.isTraceEnabled())
                    LOG.trace("{}: {}: Response received to unsent request: {}", new Object[]{
                        provider.getLocalPeerName(),
                        this,
                        message
                    });

                download.update(blockLength);
                activityListener.handleBlockReceived(this, message.getPiece(), message.getOffset(), blockLength);
                switch (reception) {
                    case VALID:
                    case INVALID:
                        activityListener.handlePieceCompleted(this, message.getPiece(), reception);
                        break;
                }

                // run();
                break;
            }

            case CANCEL: {
                PeerMessage.CancelMessage message = (PeerMessage.CancelMessage) msg;
                removeRequestReceived(message);
                break;
            }

            case EXTENDED: {
                handleExtendedMessage((PeerExtendedMessage) msg);
                break;
            }

            default: {
                close("Unrecognized message " + msg);
                break;
            }
        }
    }

    @VisibleForTesting
    public void handleExtendedMessage(@Nonnull PeerExtendedMessage msg) throws IOException {
        switch (msg.getExtendedType()) {
            case handshake: {
                PeerExtendedMessage.HandshakeMessage message = (PeerExtendedMessage.HandshakeMessage) msg;
                // this.requestsSentLimit = message.getRemoteRequestQueueLength();
                // existenceListener.addPeers(Arrays.asList());
                synchronized (lock) {
                    extendedMessageTypes = message.getSenderExtendedTypeMap();
                }
                Map<SocketAddress, byte[]> remoteAddresses = new HashMap<SocketAddress, byte[]>();
                SocketAddress remoteIp4Address = message.getSenderIp4Address();
                if (remoteIp4Address != null)
                    remoteAddresses.put(remoteIp4Address, getRemotePeerId());
                SocketAddress remoteIp6Address = message.getSenderIp6Address();
                if (remoteIp6Address != null)
                    remoteAddresses.put(remoteIp6Address, getRemotePeerId());
                existenceListener.addPeers(remoteAddresses);
                break;
            }
            case ut_pex: {
                PeerExtendedMessage.UtPexMessage message = (PeerExtendedMessage.UtPexMessage) msg;
                List<? extends SocketAddress> added = message.getAdded();
                if (added != null && !added.isEmpty()) {
                    synchronized (lock) {
                        // The remote peer already knows about these.
                        peersExchanged.addAll(added);
                    }
                    Map<SocketAddress, byte[]> peers = new HashMap<SocketAddress, byte[]>();
                    for (SocketAddress peer : added)
                        peers.put(peer, null);
                    // LOG.info("PEX adding peers " + peers);
                    existenceListener.addPeers(peers);
                }
                break;
            }
            default: {
                close("Unrecognized message " + msg);
                break;
            }
        }
    }

    @Override
    public void handleReadComplete() throws IOException {
        run("read complete");
    }

    @Override
    public void handleWritable() throws IOException {
        run("writable");
    }

    @Override
    public void handleDisconnect() throws IOException {
        connectionListener.handlePeerDisconnected(this);
    }

    public void tick() {
        upload.tick();
        download.tick();
        // TODO: Keepalives.
    }

    @Override
    public String toString() {
        // Channel c = getChannel();
        StringBuilder buf = new StringBuilder(getTextRemotePeerId());
        buf.append('(').append(getRemoteAddress()).append(')');
        buf
                .append(" [R=")
                .append((isChoking() ? "C" : "c"))
                .append((isInterested() ? "I" : "i"))
                .append("|L=")
                .append((isChoked(0) ? "C" : "c"))
                .append((isInteresting() ? "I" : "i"))
                .append("|")
                .append(getAvailablePieceCount())
                .append("]");
        buf.append(" queue=").append(requestsSent.size()).append("/");
        synchronized (lock) {
            buf.append(requestsSentLimit);
        }
        buf.append(" ul/dl=").append(getULRate().getRate(TimeUnit.SECONDS)).append("/").append(getDLRate().getRate(TimeUnit.SECONDS));
        return buf.toString();
    }
}
