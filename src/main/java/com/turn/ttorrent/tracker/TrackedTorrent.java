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
package com.turn.ttorrent.tracker;

import com.turn.ttorrent.common.Peer;
import com.turn.ttorrent.common.Torrent;

import com.turn.ttorrent.common.TorrentUtils;
import com.turn.ttorrent.common.protocol.TrackerMessage.AnnounceEvent;
import io.netty.util.internal.PlatformDependent;
import java.io.UnsupportedEncodingException;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentMap;

import java.util.concurrent.TimeUnit;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tracked torrents are torrent for which we don't expect to have data files
 * for.
 *
 * <p>
 * {@link TrackedTorrent} objects are used by the BitTorrent tracker to
 * represent a torrent that is announced by the tracker. As such, it is not
 * expected to point to any valid local data like. It also contains some
 * additional information used by the tracker to keep track of which peers
 * exchange on it, etc.
 * </p>
 *
 * @author mpetazzoni
 */
public class TrackedTorrent {

    private static final Logger LOG = LoggerFactory.getLogger(TrackedTorrent.class);
    /** Minimum announce interval requested from peers, in seconds. */
    public static final int MIN_ANNOUNCE_INTERVAL_SECONDS = 5;
    /** Default number of peers included in a tracker response. */
    private static final int DEFAULT_ANSWER_NUM_PEERS = 30;
    /** Default announce interval requested from peers, in seconds. */
    private static final int DEFAULT_ANNOUNCE_INTERVAL_SECONDS = 10;
    @CheckForNull
    private final String name;
    @Nonnull
    private final String infoHash;
    private long announceInterval;
    /** Peers currently exchanging on this torrent. */
    private final ConcurrentMap<SocketAddress, TrackedPeer> peers = PlatformDependent.newConcurrentHashMap();

    public TrackedTorrent(@CheckForNull String name, @Nonnull byte[] infoHash) {
        this.name = name;
        this.infoHash = TorrentUtils.toHex(infoHash);
        setAnnounceInterval(DEFAULT_ANNOUNCE_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    public TrackedTorrent(@Nonnull Torrent torrent) {
        this(torrent.getName(), torrent.getInfoHash());
    }

    @CheckForNull
    public String getName() {
        return name;
    }

    @Nonnull
    public String getHexInfoHash() {
        return infoHash;
    }

    /**
     * Returns the map of all peers currently exchanging on this torrent.
     */
    @Nonnull
    public Iterable<? extends TrackedPeer> getPeers() {
        return peers.values();
    }

    /**
     * Retrieve a peer exchanging on this torrent.
     *
     * @param peerId The hexadecimal representation of the peer's ID.
     */
    @CheckForNull
    public TrackedPeer getPeer(@Nonnull SocketAddress address) {
        return peers.get(address);
    }

    /**
     * Add a peer exchanging on this torrent.
     *
     * @param peer The new Peer involved with this torrent.
     */
    public void addPeer(@Nonnull TrackedPeer peer) {
        this.peers.put(peer.getPeerAddress(), peer);
    }

    /**
     * Remove a peer from this torrent's swarm.
     *
     * @param peerId The hexadecimal representation of the peer's ID.
     */
    public TrackedPeer removePeer(@Nonnull SocketAddress address) {
        return peers.remove(address);
    }

    /**
     * Count the number of seeders (peers in the COMPLETED state) on this
     * torrent.
     */
    public int seeders() {
        int count = 0;
        for (TrackedPeer peer : this.peers.values()) {
            if (peer.isCompleted()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Count the number of leechers (non-COMPLETED peers) on this torrent.
     */
    public int leechers() {
        int count = 0;
        for (TrackedPeer peer : this.peers.values()) {
            if (!peer.isCompleted()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Returns the announce interval for this torrent, in milliseconds.
     */
    @Nonnegative
    public long getAnnounceInterval() {
        return this.announceInterval;
    }

    public long getPeerExpiryInterval() {
        return getAnnounceInterval() * 2;
    }

    /**
     * Set the announce interval for this torrent.
     *
     * @param interval New announce interval, in seconds.
     */
    public void setAnnounceInterval(int interval, @Nonnull TimeUnit unit) {
        if (interval <= 0) {
            throw new IllegalArgumentException("Invalid announce interval");
        }

        long announceInterval = unit.toMillis(interval);
        if (announceInterval < 0 || announceInterval > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Illegal (overflow) timeunit " + announceInterval);
        this.announceInterval = announceInterval;
    }

    /**
     * Update this torrent's swarm from an announce event.
     *
     * <p>
     * This will automatically create a new peer on a 'started' announce event,
     * and remove the peer on a 'stopped' announce event.
     * </p>
     *
     * @param event The reported event. If <em>null</em>, means a regular
     * interval announce event, as defined in the BitTorrent specification.
     * @param peerId The byte-encoded peer ID.
     * @param hexPeerId The hexadecimal representation of the peer's ID.
     * @param ip The peer's IP address.
     * @param port The peer's inbound port.
     * @param uploaded The peer's reported uploaded byte count.
     * @param downloaded The peer's reported downloaded byte count.
     * @param left The peer's reported left to download byte count.
     * @return The peer that sent us the announce request.
     */
    public TrackedPeer update(AnnounceEvent event,
            InetSocketAddress peerAddress, byte[] peerId,
            long uploaded, long downloaded, long left) throws UnsupportedEncodingException {
        TrackedPeerState state = TrackedPeerState.UNKNOWN;

        TrackedPeer trackedPeer;
        if (AnnounceEvent.STARTED.equals(event)) {
            trackedPeer = new TrackedPeer(peerAddress, peerId);
            state = TrackedPeerState.STARTED;
            this.addPeer(trackedPeer);
        } else if (AnnounceEvent.STOPPED.equals(event)) {
            trackedPeer = removePeer(peerAddress);
            state = TrackedPeerState.STOPPED;
        } else if (AnnounceEvent.COMPLETED.equals(event)) {
            trackedPeer = getPeer(peerAddress);
            state = TrackedPeerState.COMPLETED;
        } else if (AnnounceEvent.NONE.equals(event)) {
            trackedPeer = getPeer(peerAddress);
            // TODO: There is a chance this will change COMPLETED -> STARTED
            state = TrackedPeerState.STARTED;
        } else {
            throw new IllegalArgumentException("Unexpected announce event type!");
        }

        trackedPeer.update(this, state, uploaded, downloaded, left);
        return trackedPeer;
    }

    /**
     * Get a list of peers we can return in an announce response for this
     * torrent.
     *
     * @param peer The peer making the request, so we can exclude it from the
     * list of returned peers.
     * @return A list of peers we can include in an announce response.
     */
    public List<? extends Peer> getSomePeers(TrackedPeer client, int numWant) {
        numWant = Math.min(numWant, DEFAULT_ANSWER_NUM_PEERS);

        // Extract answerPeers random peers
        List<TrackedPeer> candidates = new ArrayList<TrackedPeer>(peers.values());
        Collections.shuffle(candidates);

        List<Peer> out = new ArrayList<Peer>(numWant);
        long now = System.currentTimeMillis();
        // LOG.info("Client PeerAddress is " + client.getPeerAddress());
        for (TrackedPeer candidate : candidates) {
            // LOG.info("Candidate PeerAddress is " + candidate.getPeerAddress());
            // Collect unfresh peers, and obviously don't serve them as well.
            if (!candidate.isFresh(now, getPeerExpiryInterval())) {
                LOG.debug("Collecting stale peer {}...", candidate.getPeerAddress());
                peers.remove(candidate.getPeerAddress(), candidate);
                continue;
            }

            // Don't include the requesting peer in the answer.
            if (client.getPeerAddress().equals(candidate.getPeerAddress())) {
                if (!client.equals(candidate)) {
                    LOG.debug("Collecting superceded peer {}...", candidate);
                    removePeer(candidate.getPeerAddress());
                }
                continue;
            }

            out.add(new Peer(candidate.getPeerAddress(), candidate.getPeerId()));
            if (out.size() >= numWant)
                break;
        }

        LOG.trace("Some peers are {}", out);
        return out;
    }

    /**
     * Remove unfresh peers from this torrent.
     *
     * <p>
     * Collect and remove all non-fresh peers from this torrent. This is
     * usually called by the periodic peer collector of the BitTorrent tracker.
     * </p>
     */
    @Nonnegative
    public int collectUnfreshPeers() {
        long now = System.currentTimeMillis();
        int count = 0;
        for (TrackedPeer peer : peers.values()) {
            if (!peer.isFresh(now, getPeerExpiryInterval())) {
                peers.remove(peer.getPeerAddress(), peer);
                count++;
            }
        }
        return count;
    }

    @Override
    public String toString() {
        return getName() + " (" + peers.size() + " peers, interval=" + getAnnounceInterval() + ")";
    }
}