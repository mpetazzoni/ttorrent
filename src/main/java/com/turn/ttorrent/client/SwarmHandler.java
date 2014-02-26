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

import com.turn.ttorrent.client.peer.PeerConnectionListener;
import com.turn.ttorrent.client.io.PeerMessage;

import com.turn.ttorrent.client.peer.PieceHandler;
import com.turn.ttorrent.client.peer.PeerActivityListener;
import com.turn.ttorrent.client.peer.RateComparator;
import com.turn.ttorrent.client.peer.PeerHandler;
import com.turn.ttorrent.client.peer.Instrumentation;
import com.turn.ttorrent.client.peer.Rate;
import com.turn.ttorrent.common.Peer;
import com.turn.ttorrent.common.TorrentUtils;
import io.netty.channel.Channel;
import io.netty.util.internal.PlatformDependent;
import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;
import javax.annotation.concurrent.GuardedBy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Incoming peer connections service.
 *
 * <p>
 * Every BitTorrent client, BitTorrent being a peer-to-peer protocol, listens
 * on a port for incoming connections from other peers sharing the same
 * torrent.
 * </p>
 *
 * <p>
 * This ConnectionHandler implements this service and starts a listening socket
 * in the first available port in the default BitTorrent client port range
 * 6881-6889. When a peer connects to it, it expects the BitTorrent handshake
 * message, parses it and replies with our own handshake.
 * </p>
 *
 * <p>
 * Outgoing connections to other peers are also made through this service,
 * which handles the handshake procedure with the remote peer. Regardless of
 * the direction of the connection, once this handshake is successful, all
 * {@link IncomingConnectionListener}s are notified and passed the connected
 * socket and the remote peer ID.
 * </p>
 *
 * <p>
 * This class does nothing more. All further peer-to-peer communication happens
 * in the {@link com.turn.ttorrent.client.peer.PeerExchange PeerExchange}
 * class.
 * </p>
 *
 * @author mpetazzoni
 * @see <a href="http://wiki.theory.org/BitTorrentSpecification#Handshake">BitTorrent handshake specification</a>
 */
public class SwarmHandler implements Runnable, PeerConnectionListener, PeerPieceProvider, PeerActivityListener {

    private static final Logger LOG = LoggerFactory.getLogger(SwarmHandler.class);

    private static class PeerInformation {

        // Yes, the reference is volatile, not the data.
        @CheckForNull
        private volatile byte[] remotePeerId;
        private volatile long reconnectTime;
    }
    /** Peers unchoking frequency, in seconds. Current BitTorrent specification
     * recommends 10 seconds to avoid choking fibrilation. */
    private static final long UNCHOKE_DELAY = TimeUnit.SECONDS.toMillis(10);
    /** Optimistic unchokes are done every 2 loop iterations, i.e. every
     * 2*UNCHOKING_FREQUENCY seconds. */
    private static final long OPTIMISTIC_UNCHOKE_DELAY = TimeUnit.SECONDS.toMillis(32);
    private static final int MAX_DOWNLOADERS_UNCHOKE = 4;
    private static final long RECONNECT_DELAY = TimeUnit.SECONDS.toMillis(30);
    /** End-game trigger ratio.
     *
     * <p>
     * Eng-game behavior (requesting already requested pieces from available
     * and ready peers to try to speed-up the end of the transfer) will only be
     * enabled when the ratio of completed pieces over total pieces in the
     * torrent is over this value.
     * </p>
     */
    private static final float END_GAME_COMPLETION_RATIO = 0.95f;
    private final TorrentHandler torrent;
    // Keys are InetSocketAddress or HexPeerId
    private final ConcurrentMap<SocketAddress, PeerInformation> knownPeers = PlatformDependent.newConcurrentHashMap();
    private final ConcurrentMap<String, PeerHandler> connectedPeers = PlatformDependent.newConcurrentHashMap();
    private final AtomicLong uploaded = new AtomicLong(0);
    private final AtomicLong downloaded = new AtomicLong(0);
    private final AtomicIntegerArray availablePieces;
    @GuardedBy("lock")
    private final BitSet requestedPieces;
    @GuardedBy("lock")
    private final Set<PieceHandler.AnswerableRequestMessage> partialPieces = new HashSet<PieceHandler.AnswerableRequestMessage>();
    // We only care about global rarest pieces for peer selection or opportunistic unchoking.
    // private final BitSet rarestPieces;
    // private int rarestPiecesAvailability = 0;
    @GuardedBy("future")
    private Future<?> future;
    @GuardedBy("lock")
    private long unchokeTime = 0;
    @GuardedBy("lock")
    private long optimisticUnchokeTime = 0;
    private long tickTime = 0;
    private final Object lock = new Object();

    /**
     * Create and start a new listening service for out torrent, reporting
     * with our peer ID on the given address.
     *
     * <p>
     * This binds to the first available port in the client port range
     * PORT_RANGE_START to PORT_RANGE_END.
     * </p>
     *
     * @param torrent The torrent shared by this client.
     */
    SwarmHandler(@Nonnull TorrentHandler torrent) {
        this.torrent = torrent;
        this.availablePieces = new AtomicIntegerArray(torrent.getPieceCount());
        this.requestedPieces = new BitSet(torrent.getPieceCount());
        // this.rarestPieces = new BitSet(torrent.getPieceCount());
    }

    @Nonnull
    public Client getClient() {
        return torrent.getClient();
    }

    @Override
    public String getLocalPeerName() {
        return getClient().getEnvironment().getLocalPeerName();
    }

    @Override
    public Instrumentation getInstrumentation() {
        return getClient().getEnvironment().getInstrumentation();
    }

    @Nonnull
    private Random getRandom() {
        return getClient().getEnvironment().getRandom();
    }

    @Nonnegative
    public int getPeerCount() {
        return knownPeers.size();
    }

    public void addPeers(@Nonnull Iterable<? extends SocketAddress> peerAddresses) {
        if (LOG.isTraceEnabled())
            LOG.trace("{}: Adding peers {}", getLocalPeerName(), peerAddresses);
        for (SocketAddress peerAddress : peerAddresses)
            knownPeers.putIfAbsent(peerAddress, new PeerInformation());
        // run();
    }

    @Nonnull
    public Iterable<? extends PeerHandler> getConnectedPeers() {
        return connectedPeers.values();
    }

    @Nonnegative
    public int getConnectedPeerCount() {
        return connectedPeers.size();
    }

    /**
     * Get the number of bytes uploaded for this torrent.
     */
    @Nonnegative
    public long getUploaded() {
        return uploaded.get();
    }

    /**
     * Get the number of bytes downloaded for this torrent.
     *
     * <p>
     * <b>Note:</b> this could be more than the torrent's length, and should
     * not be used to determine a completion percentage.
     * </p>
     */
    @Nonnegative
    public long getDownloaded() {
        return downloaded.get();
    }

    @Nonnegative
    public int getAvailablePieceCount() {
        int count = 0;
        for (int i = 0; i < torrent.getPieceCount(); i++) {
            if (this.availablePieces.get(i) > 0)
                count++;
        }
        return count;
    }

    public int setAvailablePiece(@Nonnegative int piece, boolean available) {
        if (available) {
            return availablePieces.incrementAndGet(piece);
        } else {
            for (;;) {
                int current = availablePieces.get(piece);
                if (current <= 0)
                    return 0;
                int next = current - 1;
                if (availablePieces.compareAndSet(piece, current, next))
                    return next;
            }
        }
    }

    /**
     * Return a copy of the requested pieces bitset.
     */
    @Nonnull
    public BitSet getRequestedPieces() {
        synchronized (lock) {
            return (BitSet) this.requestedPieces.clone();
        }
    }

    @Nonnegative
    public int getRequestedPieceCount() {
        synchronized (lock) {
            return requestedPieces.cardinality();
        }
    }

    /*
     public boolean isRequestedPiece(int index) {
     synchronized (lock) {
     return requestedPieces.get(index);
     }
     }
     */
    private void andNotRequestedPieces(@Nonnull BitSet b) {
        synchronized (lock) {
            b.andNot(requestedPieces);
        }
    }

    /**
     * Connect to the given peer and perform the BitTorrent handshake.
     *
     * <p>
     * Submits an asynchronous connection task to the outbound connections
     * executor to connect to the given peer.
     * </p>
     *
     * @param peer The peer to connect to.
     */
    public void connect(SocketAddress address) {
        LOG.info("{}: Attempting to connect to {} for {}", new Object[]{
            getLocalPeerName(),
            address, torrent
        });
        getClient().getPeerClient().connect(this, torrent.getInfoHash(), address);
    }

    public void start() {
        synchronized (lock) {
            long ms = Rate.INTERVAL_MS;
            ms = Math.min(ms, UNCHOKE_DELAY);
            ms = Math.min(ms, OPTIMISTIC_UNCHOKE_DELAY);
            ms = Math.min(ms, RECONNECT_DELAY);
            future = getClient().getEnvironment().getSchedulerService().scheduleWithFixedDelay(this, 0, ms, TimeUnit.MILLISECONDS);
        }
    }

    public void stop() {
        synchronized (lock) {
            if (future != null) {
                future.cancel(true);
                future = null;
            }
        }
    }

    // TODO: Periodic / step function.
    @Override
    public void run() {
        if (LOG.isTraceEnabled())
            LOG.trace("{}: Run: peers={}, connected={}, completed={}/{}",
                    new Object[]{
                getLocalPeerName(),
                knownPeers.keySet(), getConnectedPeers(),
                torrent.getCompletedPieceCount(), torrent.getPieceCount()
            });
        boolean unchoke = false;
        boolean optimisticUnchoke = false;
        boolean tick = false;
        long now = System.currentTimeMillis();
        synchronized (lock) {
            if (unchokeTime + UNCHOKE_DELAY < now) {
                unchokeTime = now;
                unchoke = true;
            }
            if (optimisticUnchokeTime + OPTIMISTIC_UNCHOKE_DELAY < now) {
                optimisticUnchokeTime = now;
                optimisticUnchoke = true;
            }
            if (tickTime + Rate.INTERVAL_MS < now) {
                tickTime = now;
                tick = true;
            }
        }

        if (!torrent.isComplete()) {
            // Attempt to connect to the peer if and only if:
            //   - We're not already connected or connecting to it;
            //   - We're not a seeder (we leave the responsibility
            //	   of connecting to peers that need to download
            //     something).
            for (Map.Entry<SocketAddress, PeerInformation> e : knownPeers.entrySet()) {
                PeerInformation peerInformation = e.getValue();
                if (peerInformation.reconnectTime > now)
                    continue;
                byte[] remotePeerId = peerInformation.remotePeerId;
                if (remotePeerId != null)
                    if (connectedPeers.containsKey(TorrentUtils.toHex(remotePeerId)))
                        continue;
                connect(e.getKey());
            }
        }

        if (unchoke)
            unchokePeers(optimisticUnchoke);

        for (PeerHandler peer : getConnectedPeers()) {
            try {
                // This is the call which is likely to cause the most trouble.
                peer.run();
            } catch (IOException e) {
                LOG.error(getLocalPeerName() + ": Peer " + peer + " threw.", e);
            }
            if (tick)
                peer.tick();
        }
    }

    /**
     * Retrieve a peer comparator.
     *
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
    @Nonnull
    private Comparator<PeerHandler> getPeerRateComparator() {
        switch (torrent.getState()) {
            case SHARING:
                return new RateComparator.DLRateComparator();
            case SEEDING:
                return new RateComparator.ULRateComparator();
            default:
                throw new IllegalStateException("Client is neither sharing nor "
                        + "seeding, we shouldn't be comparing peers at this point.");
        }
    }

    /**
     * Unchoke connected peers.
     *
     * <p>
     * This is one of the "clever" places of the BitTorrent client. Every
     * OPTIMISTIC_UNCHOKING_FREQUENCY seconds, we decide which peers should be
     * unchoked and authorized to grab pieces from us.
     * </p>
     *
     * <p>
     * Reciprocation (tit-for-tat) and upload capping is implemented here by
     * carefully choosing which peers we unchoke, and which peers we choke.
     * </p>
     *
     * <p>
     * The four peers with the best download rate and are interested in us get
     * unchoked. This maximizes our download rate as we'll be able to get data
     * from the four "best" peers quickly, while allowing these peers to
     * download from us and thus reciprocate their generosity.
     * </p>
     *
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
    private void unchokePeers(boolean optimistic) {
        // Build a set of all connected peers, we don't care about peers we're
        // not connected to.
        List<PeerHandler> candidates = new ArrayList<PeerHandler>();
        for (PeerHandler peer : getConnectedPeers()) {
            if (peer.isInterested())
                candidates.add(peer);
            else
                peer.choke();
        }

        if (LOG.isTraceEnabled())
            LOG.trace("{}: Running unchokePeers() on {} connected peers.", getLocalPeerName(), candidates.size());
        Collections.sort(candidates, getPeerRateComparator());
        // LOG.info("{}: Candidates are {}", getLocalPeerName(), candidates);

        int downloaders = 0;
        // We're interested in the top downloaders first, so use a descending set.
        int i = 0;
        for (/* */; i < candidates.size(); i++) {
            if (downloaders++ >= MAX_DOWNLOADERS_UNCHOKE)
                break;
            PeerHandler peer = candidates.get(i);
            peer.unchoke();
        }

        // Actually choke all chosen peers (if any), except the eventual
        // optimistic unchoke.
        int nchoked = candidates.size() - i;
        if (nchoked > 0) {
            int unchoke;
            if (optimistic) {
                unchoke = i + getRandom().nextInt(nchoked);
                if (LOG.isTraceEnabled()) {
                    PeerHandler peer = candidates.get(unchoke);
                    LOG.trace("Optimistic unchoke of {}.", peer);
                }
            } else {
                unchoke = -1;
            }
            for (/* */; i < candidates.size(); i++) {
                if (i == unchoke)
                    candidates.get(i).unchoke();
                else
                    candidates.get(i).choke();
            }
        }
    }

    /**
     * Computes the set of rarest pieces from the interesting set.
     */
    private int computeRarestPieces(@Nonnull BitSet rarest, @Nonnull BitSet interesting) {
        rarest.clear();
        int rarestAvailability = Integer.MAX_VALUE;
        for (int i = interesting.nextSetBit(0); i >= 0;
                i = interesting.nextSetBit(i + 1)) {
            int availability = availablePieces.get(i);
            // This looks weird, but: The bit wouldn't be set in interesting if availability was 0.
            // So we got a miscount somewhere (entirely possible) and we patch up here.
            if (availability == 0)
                availability = 1;
            // Now, !interesting.isEmpty() -> !rarest.isEmpty()
            if (availability > rarestAvailability)
                continue;
            if (availability < rarestAvailability) {
                rarestAvailability = availability;
                rarest.clear();
            }
            rarest.set(i);
        }
        return rarestAvailability;
    }

    @Override
    public int getPieceCount() {
        return torrent.getPieceCount();
    }

    @Override
    public int getPieceLength(int index) {
        return torrent.getPieceLength(index);
    }

    @Override
    public int getBlockLength() {
        return torrent.getBlockLength();
    }

    @Override
    public BitSet getCompletedPieces() {
        return torrent.getCompletedPieces();
    }

    @Override
    public boolean isCompletedPiece(int index) {
        return torrent.isCompletedPiece(index);
    }

    @Override
    public void andNotCompletedPieces(BitSet out) {
        torrent.andNotCompletedPieces(out);
    }

    @Override
    public Iterable<PieceHandler.AnswerableRequestMessage> getNextPieceHandler(
            @Nonnull PeerHandler peer,
            @Nonnull BitSet peerInteresting) {
        int peerAvailable = peer.getAvailablePieceCount();
        // LOG.debug("Peer interesting is {}", peerInteresting);

        // TODO: We hold this lock for a LONG time. :-(
        // I'm fairly sure our lock acquisition order is peer then torrent.
        // We can't drop the lock earlier, else two peers will get the
        // same DownloadingPiece, and we don't reference those.
        synchronized (lock) {
            PARTIAL:
            {
                List<PieceHandler.AnswerableRequestMessage> piece = new ArrayList<PieceHandler.AnswerableRequestMessage>();
                Iterator<PieceHandler.AnswerableRequestMessage> it = partialPieces.iterator();
                while (it.hasNext()) {
                    if (piece.size() > 20)
                        break;
                    PieceHandler.AnswerableRequestMessage request = it.next();
                    if (peerInteresting.get(request.getPiece())) {
                        // An endgame might have requested it elsewhere.
                        if (!isCompletedPiece(request.getPiece())) {
                            if (LOG.isTraceEnabled())
                                LOG.trace("{}: Peer {} retrying request {}", new Object[]{
                                    getLocalPeerName(),
                                    peer, request
                                });
                            piece.add(request);
                        }

                        it.remove();
                    }
                }
                // LOG.info("Looking for partials generated " + piece);
                if (!piece.isEmpty())
                    return piece;
            }

            // TODO: Should this be before or after PARTIAL?
            BitSet interesting = (BitSet) peerInteresting.clone();
            this.andNotRequestedPieces(interesting);

            // If we didn't find interesting pieces, we need to check if we're in
            // an end-game situation. If yes, we request an already requested piece
            // to try to speed up the end.
            if (interesting.isEmpty()) {
                if (torrent.getCompletedPieceCount() < END_GAME_COMPLETION_RATIO * torrent.getPieceCount()) {
                    if (LOG.isTraceEnabled())
                        LOG.trace("{}: Not far along enough to warrant end-game mode.", getLocalPeerName());
                    return null;
                }

                interesting = (BitSet) peerInteresting.clone();
                torrent.andNotCompletedPieces(interesting);
                if (LOG.isTraceEnabled())
                    LOG.trace("{}: Possible end-game, we're about to request a piece "
                            + "that was already requested from another peer.",
                            getLocalPeerName());
            }

            if (interesting.isEmpty()) {
                if (LOG.isTraceEnabled())
                    LOG.trace("{}: No interesting piece from {}!", getLocalPeerName(), peer);
                return null;
            }

            BitSet rarestPieces = new BitSet(interesting.length());
            computeRarestPieces(rarestPieces, interesting);
            // Since interesting is nonempty, rarestPieces should be nonempty.
            // We can violate that if we have a miscount of 0 in availablePieces.
            // Pick a random piece from the rarest pieces from this peer.
            int rarestIndex = getRandom().nextInt(rarestPieces.cardinality());
            SEARCH:
            {
                // This loop will NEVER terminate "normally" because rarestIndex < rarestPieces.cardinality();
                for (int i = rarestPieces.nextSetBit(0); i >= 0;
                        i = rarestPieces.nextSetBit(i + 1)) {
                    if (rarestIndex-- == 0) {
                        rarestIndex = i;
                        break SEARCH;
                    }
                }
                // NOTREACHED
                LOG.error("{}: No rare piece from {}!", getLocalPeerName(), peer);
                return null;
            }

            if (LOG.isTraceEnabled())
                LOG.trace("{}: Peer {} has {}/{}/{} interesting piece(s); requesting {}, requests {}", new Object[]{
                    getLocalPeerName(),
                    peer,
                    interesting.cardinality(), peerAvailable, torrent.getPieceCount(),
                    rarestIndex,
                    getRequestedPieces()
                });

            requestedPieces.set(rarestIndex);

            return new PieceHandler(this, rarestIndex);
        }
    }

    @Override
    public int addRequestTimeout(Iterable<? extends PieceHandler.AnswerableRequestMessage> requests) {
        int count = 0;
        synchronized (lock) {
            for (PieceHandler.AnswerableRequestMessage request : requests) {
                if (!isCompletedPiece(request.getPiece())) {
                    partialPieces.add(request);
                    count++;
                }
            }
        }
        return count;
    }

    private void ioBlock(@Nonnull ByteBuffer block, @Nonnegative int piece, @Nonnegative int offset) throws IOException {
        int rawLength = torrent.getTorrent().getPieceLength(piece);
        if (offset + block.remaining() > rawLength)
            throw new IllegalArgumentException("Offset "
                    + offset + "+" + block.remaining()
                    + " too large for piece " + piece
                    + " of length " + rawLength);
    }

    @Override
    public void readBlock(ByteBuffer block, int piece, int offset) throws IOException {
        ioBlock(block, piece, offset);
        long rawOffset = torrent.getTorrent().getPieceOffset(piece) + offset;
        torrent.getBucket().read(block, rawOffset);
    }

    @Override
    public void writeBlock(ByteBuffer block, int piece, int offset) throws IOException {
        ioBlock(block, piece, offset);
        long rawOffset = torrent.getTorrent().getPieceOffset(piece) + offset;
        torrent.getBucket().write(block, rawOffset);
    }

    @Override
    public boolean validateBlock(ByteBuffer block, int piece) throws IOException {
        // LOG.trace("Validating data for {}...", this);
        return torrent.getTorrent().isPieceValid(piece, block);
    }

    /** PeerConnectionListener handler(s). ********************************/
    /**
     * Retrieve a SharingPeer object from the given peer specification.
     *
     * <p>
     * This function tries to retrieve an existing peer object based on the
     * provided peer specification or otherwise instantiates a new one and adds
     * it to our peer repository.
     * </p>
     *
     * This method takes two @Nonnull arguments, because Peer has a
     * @CheckForNull on {@link Peer#getPeerId()}.
     */
    @Override
    public PeerHandler handlePeerConnectionCreated(@Nonnull Channel channel, @Nonnull byte[] remotePeerId) {
        SocketAddress remoteAddress = channel.remoteAddress();
        String remoteHexPeerId = TorrentUtils.toHex(remotePeerId);

        if (Arrays.equals(remotePeerId, getClient().getLocalPeerId()))
            throw new IllegalArgumentException("Cannot connect to self.");

        // This is a valid InetAddress, but a random highport.
        // peers.add(remoteAddress);

        // Peer peer = new Peer(remoteAddress, remotePeerId);
        // LOG.trace("Searching for address {}, peerId {}...", remoteAddress, TorrentUtils.toHex(remotePeerId));

        PeerInformation peerInformation = knownPeers.get(remoteAddress);
        if (peerInformation != null)
            peerInformation.remotePeerId = remotePeerId;

        PeerHandler peerHandler = connectedPeers.get(remoteHexPeerId);
        if (peerHandler != null) {
            if (LOG.isTraceEnabled())
                LOG.trace("{}: Found peer (by PeerId): {}.", getLocalPeerName(), peerHandler);
            return null;
        }

        peerHandler = new PeerHandler(remotePeerId, channel, this, this, this);
        if (LOG.isTraceEnabled())
            LOG.trace("{}: Created new peer: {}.", getLocalPeerName(), peerHandler);

        return peerHandler;
    }

    /**
     * Handle a new peer connection.
     *
     * <p>
     * This handler is called once the connection has been successfully
     * established and the handshake exchange made. This generally simply means
     * binding the peer to the socket, which will put in place the communication
     * thread and logic with this peer.
     * </p>
     *
     * @param channel The connected socket channel to the remote peer. Note
     * that if the peer somehow rejected our handshake reply, this socket might
     * very soon get closed, but this is handled down the road.
     * @param peerId The byte-encoded peerId extracted from the peer's
     * handshake, after validation.
     * @see com.turn.ttorrent.client.peer.SharingPeer
     */
    @Override
    public void handlePeerConnectionReady(PeerHandler peer) {
        try {
            LOG.debug("{}: New peer connection with {} [{}/{}].",
                    new Object[]{
                getLocalPeerName(),
                peer,
                getConnectedPeerCount(),
                getPeerCount()
            });

            PeerHandler prev = connectedPeers.put(peer.getHexRemotePeerId(), peer);
            if (prev != null)
                prev.close();

            // Give the peer a chance to send a bitfield message.
            peer.run();
        } catch (Exception e) {
            LOG.warn("Could not handle new peer connection "
                    + "with {}: {}", peer, e.getMessage());
        }
    }

    /**
     * Handle a failed peer connection.
     *
     * <p>
     * If an outbound connection failed (could not connect, invalid handshake,
     * etc.), remove the peer from our known peers.
     * </p>
     *
     * @param peer The peer we were trying to connect with.
     * @param cause The exception encountered when connecting with the peer.
     */
    @Override
    public void handlePeerConnectionFailed(SocketAddress remoteAddress, Throwable cause) {
        LOG.warn("{}: Could not connect to {}: {}.", new Object[]{
            getLocalPeerName(),
            remoteAddress, (cause == null) ? "(cause not specified)" : cause.getMessage()
        });
        // No need to clean up the connectedPeers map here -
        // PeerHandler is only created in PeerHandshakeHandler.

        PeerInformation peerInformation = knownPeers.get(remoteAddress);
        if (peerInformation != null)
            peerInformation.reconnectTime = System.currentTimeMillis() + RECONNECT_DELAY;
    }

    /** PeerActivityListener handler(s). *************************************/
    /**
     * Peer choked handler.
     *
     * <p>
     * When a peer chokes, the requests made to it are cancelled and we need to
     * mark the eventually piece we requested from it as available again for
     * download tentative from another peer.
     * </p>
     *
     * @param peer The peer that choked.
     */
    @Override
    public void handlePeerChoking(PeerHandler peer) {
        if (LOG.isTraceEnabled())
            LOG.trace("Peer {} choked, we now have {} outstanding "
                    + "request(s): {}",
                    new Object[]{
                peer,
                getRequestedPieceCount(),
                getRequestedPieces()
            });
    }

    /**
     * Peer ready handler.
     *
     * <p>
     * When a peer becomes ready to accept piece block requests, select a piece
     * to download and go for it.
     * </p>
     *
     * @param peer The peer that became ready.
     */
    @Override
    public void handlePeerUnchoking(PeerHandler peer) {
        if (LOG.isTraceEnabled())
            LOG.trace("{}: Peer {} is ready and has {}/{} piece(s).", new Object[]{
                getLocalPeerName(),
                peer,
                peer.getAvailablePieceCount(),
                torrent.getPieceCount()
            });
    }

    /**
     * Piece availability handler.
     *
     * <p>
     * Handle updates in piece availability from a peer's HAVE message. When
     * this happens, we need to mark that piece as available from the peer.
     * </p>
     *
     * @param peer The peer we got the update from.
     * @param piece The piece that became available.
     */
    @Override
    public void handlePieceAvailability(PeerHandler peer, int piece) {
        setAvailablePiece(piece, true);
        if (LOG.isTraceEnabled())
            LOG.trace("{}: Peer {} contributes {}/{} piece(s) "
                    + "[completed={}, available={}/{}] "
                    + "[connected={}/{}]",
                    new Object[]{
                getLocalPeerName(),
                peer,
                peer.getAvailablePieceCount(),
                torrent.getPieceCount(),
                torrent.getCompletedPieceCount(),
                getAvailablePieceCount(),
                torrent.getPieceCount(),
                getConnectedPeerCount(),
                getPeerCount()
            });
    }

    /**
     * Bit field availability handler.
     *
     * <p>
     * Handle updates in piece availability from a peer's BITFIELD message.
     * When this happens, we need to mark in all the pieces the peer has that
     * they can be reached through this peer, thus augmenting the global
     * availability of pieces.
     * </p>
     *
     * @param peer The peer we got the update from.
     * @param availablePieces The pieces availability bit field of the peer.
     */
    @Override
    public void handleBitfieldAvailability(PeerHandler peer,
            BitSet prevAvailablePieces,
            BitSet currAvailablePieces) {

        // Record that the peer no longer has all the pieces it previously told us it had.
        for (int i = prevAvailablePieces.nextSetBit(0); i >= 0;
                i = prevAvailablePieces.nextSetBit(i + 1)) {
            if (!currAvailablePieces.get(i))
                setAvailablePiece(i, false);
        }

        // Record that the peer has all the pieces it told us it had.
        for (int i = currAvailablePieces.nextSetBit(0); i >= 0;
                i = currAvailablePieces.nextSetBit(i + 1)) {
            if (!prevAvailablePieces.get(i))
                setAvailablePiece(i, true);
        }

        // Determine if the peer is interesting for us or not, and notify it.
        BitSet interesting = currAvailablePieces;
        torrent.andNotCompletedPieces(interesting);
        this.andNotRequestedPieces(interesting);

        /*
         if (interesting.isEmpty())
         peer.notInteresting();
         else
         peer.interesting();
         */

        if (LOG.isTraceEnabled())
            LOG.trace("Peer {} contributes {} piece(s) ({} interesting) "
                    + "[completed={}; available={}/{}] "
                    + "[connected={}/{}]",
                    new Object[]{
                peer,
                currAvailablePieces.cardinality(),
                interesting.cardinality(),
                torrent.getCompletedPieceCount(),
                getAvailablePieceCount(),
                torrent.getPieceCount(),
                getConnectedPeerCount(),
                getPeerCount()
            });
    }

    /**
     * Block upload handler.
     *
     * <p>
     * When a block has been sent to a peer, we just record that we sent that
     * many bytes. If the piece is valid on the peer's side, it will send us a
     * HAVE message and we'll record that the piece is available on the peer at
     * that moment (see <code>handlePieceAvailability()</code>).
     * </p>
     *
     * @param peer The peer we got this piece from.
     * @param piece The piece in question.
     */
    @Override
    public void handleBlockSent(PeerHandler peer, int piece, int offset, int length) {
        this.uploaded.addAndGet(length);
    }

    @Override
    public void handleBlockReceived(PeerHandler peer, int piece, int offset, int length) {
        this.downloaded.addAndGet(length);
    }

    /**
     * Piece download completion handler.
     *
     * <p>
     * When a piece is completed, and valid, we announce to all connected peers
     * that we now have this piece.
     * </p>
     *
     * <p>
     * We use this handler to identify when all of the pieces have been
     * downloaded. When that's the case, we can start the seeding period, if
     * any.
     * </p>
     *
     * @param peer The peer we got the piece from.
     * @param piece The piece in question.
     */
    @Override
    public void handlePieceCompleted(PeerHandler peer, int piece, PieceHandler.Reception reception)
            throws IOException {
        // Regardless of validity, record the number of bytes downloaded and
        // mark the piece as not requested anymore
        synchronized (lock) {
            requestedPieces.clear(piece);
            // TODO: Not sure if this is required.
            for (Iterator<PieceHandler.AnswerableRequestMessage> it = partialPieces.iterator(); it.hasNext(); /**/) {
                PieceHandler.AnswerableRequestMessage request = it.next();
                if (request.getPiece() == piece)
                    it.remove();
            }
        }

        if (LOG.isTraceEnabled())
            LOG.trace("{}: Completed download of {} from {} ({}). We now have {}/{} pieces and {} outstanding requests: {}",
                    new Object[]{
                getLocalPeerName(),
                piece, peer, reception,
                torrent.getCompletedPieceCount(),
                torrent.getPieceCount(),
                getRequestedPieceCount(),
                getRequestedPieces()
            });

        if (reception == PieceHandler.Reception.VALID) {
            // Make sure the piece is marked as completed in the torrent
            // Note: this is required because the order the
            // PeerActivityListeners are called is not defined, and we
            // might be called before the torrent's piece completion
            // handler is.
            torrent.setCompletedPiece(piece);

            // Send a HAVE message to all connected peers
            PeerMessage have = new PeerMessage.HaveMessage(piece);
            for (PeerHandler remote : getConnectedPeers())
                remote.send(have, true);
        } else {
            LOG.warn("Downloaded piece#{} from {} was not valid ;-(",
                    piece, peer);
        }

        if (torrent.isComplete()) {
            LOG.info("{}: {}: Last piece validated and completed, finishing download.",
                    getLocalPeerName(), torrent);

            // Cancel all remaining outstanding requests
            for (PeerHandler remote : getConnectedPeers())
                remote.cancelRequestsSent("torrent completed");

            // TODO: This need locking.
            torrent.finish();
        }
    }

    /**
     * Peer disconnection handler.
     *
     * <p>
     * When a peer disconnects, we need to mark in all of the pieces it had
     * available that they can't be reached through this peer anymore.
     * </p>
     *
     * @param peer The peer we got this piece from.
     */
    @Override
    public void handlePeerDisconnected(PeerHandler peer) {
        BitSet peerAvailablePieces = peer.getAvailablePieces();
        for (int i = peerAvailablePieces.nextSetBit(0); i >= 0;
                i = peerAvailablePieces.nextSetBit(i + 1)) {
            setAvailablePiece(i, false);
        }

        peer.rejectRequestsSent("peer disconnected");

        if (LOG.isDebugEnabled())
            LOG.debug("{}: Peer {} went away with {} piece(s) "
                    + "[completed={}; available={}/{}] "
                    + "[connected={}/{}]",
                    new Object[]{
                getLocalPeerName(),
                peer,
                peer.getAvailablePieceCount(),
                torrent.getCompletedPieceCount(),
                getAvailablePieceCount(),
                torrent.getPieceCount(),
                getConnectedPeerCount(),
                getPeerCount()
            });

        connectedPeers.remove(peer.getHexRemotePeerId(), peer);

        PeerInformation peerInformation = knownPeers.get(peer.getRemoteAddress());
        if (peerInformation != null)
            peerInformation.reconnectTime = System.currentTimeMillis() + RECONNECT_DELAY;
        else
            for (Map.Entry<SocketAddress, PeerInformation> e : knownPeers.entrySet()) {
                peerInformation = e.getValue();
                if (Arrays.equals(peerInformation.remotePeerId, peer.getRemotePeerId()))
                    peerInformation.reconnectTime = System.currentTimeMillis() + RECONNECT_DELAY;
            }
    }

    @Override
    public void handleIOException(PeerHandler peer, IOException ioe) {
        LOG.warn("I/O error while exchanging data with " + peer + ", "
                + "closing connection with it!", ioe);
        peer.close();
        // This should be done by handlePeerDisconnected but let's double up.
        connectedPeers.remove(peer.getHexRemotePeerId(), peer);
    }

    public void info(boolean verbose) {
        double dl = 0;
        double ul = 0;
        for (PeerHandler peer : getConnectedPeers()) {
            dl += peer.getDLRate().rate(TimeUnit.SECONDS);
            ul += peer.getULRate().rate(TimeUnit.SECONDS);
        }

        LOG.info("{} {} {} {}/{} pieces ({}%) [req {}/{}] with {}/{} peers at {}/{} kB/s.",
                new Object[]{
            getLocalPeerName(),
            torrent.getState().name(),
            TorrentUtils.toHex(torrent.getInfoHash()),
            torrent.getCompletedPieceCount(),
            getPieceCount(),
            String.format("%.2f", torrent.getCompletion()),
            getRequestedPieceCount(),
            getAvailablePieceCount(),
            getConnectedPeerCount(),
            getPeerCount(),
            String.format("%.2f", dl / 1024.0),
            String.format("%.2f", ul / 1024.0)
        });

        for (PeerHandler peer : getConnectedPeers())
            LOG.debug("  | {}", peer);
    }
}