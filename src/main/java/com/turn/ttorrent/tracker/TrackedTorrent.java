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
import com.turn.ttorrent.common.protocol.TrackerMessage.AnnounceRequestMessage.RequestEvent;

import java.io.UnsupportedEncodingException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

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

    private static final Logger logger =
            LoggerFactory.getLogger(TrackedTorrent.class);
    /** Minimum announce interval requested from peers, in seconds. */
    public static final int MIN_ANNOUNCE_INTERVAL_SECONDS = 5;
    /** Default number of peers included in a tracker response. */
    private static final int DEFAULT_ANSWER_NUM_PEERS = 30;
    /** Default announce interval requested from peers, in seconds. */
    private static final int DEFAULT_ANNOUNCE_INTERVAL_SECONDS = 10;
    private final Torrent torrent;
    private int announceInterval = TrackedTorrent.DEFAULT_ANNOUNCE_INTERVAL_SECONDS;
    /** Peers currently exchanging on this torrent. */
    private final ConcurrentMap<Peer, TrackedPeer> peers = new ConcurrentHashMap<Peer, TrackedPeer>();

    public TrackedTorrent(Torrent torrent) {
        this.torrent = torrent;
    }

    public String getName() {
        return torrent.getName();
    }

    public String getHexInfoHash() {
        return torrent.getHexInfoHash();
    }

    /**
     * Returns the map of all peers currently exchanging on this torrent.
     */
    @Nonnull
    public Map<? extends Peer, ? extends TrackedPeer> getPeers() {
        return this.peers;
    }

    /**
     * Add a peer exchanging on this torrent.
     *
     * @param peer The new Peer involved with this torrent.
     */
    public void addPeer(TrackedPeer peer) {
        this.peers.put(peer.getPeer(), peer);
    }

    /**
     * Retrieve a peer exchanging on this torrent.
     *
     * @param peerId The hexadecimal representation of the peer's ID.
     */
    public TrackedPeer getPeer(Peer peer) {
        return this.peers.get(peer);
    }

    /**
     * Remove a peer from this torrent's swarm.
     *
     * @param peerId The hexadecimal representation of the peer's ID.
     */
    public TrackedPeer removePeer(Peer peer) {
        return this.peers.remove(peer);
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
     * Remove unfresh peers from this torrent.
     *
     * <p>
     * Collect and remove all non-fresh peers from this torrent. This is
     * usually called by the periodic peer collector of the BitTorrent tracker.
     * </p>
     */
    public void collectUnfreshPeers() {
        long now = System.currentTimeMillis();
        for (TrackedPeer peer : this.peers.values()) {
            if (!peer.isFresh(now)) {
                removePeer(peer.getPeer());
            }
        }
    }

    /**
     * Get the announce interval for this torrent.
     */
    public int getAnnounceInterval() {
        return this.announceInterval;
    }

    /**
     * Set the announce interval for this torrent.
     *
     * @param interval New announce interval, in seconds.
     */
    public void setAnnounceInterval(int interval) {
        if (interval <= 0) {
            throw new IllegalArgumentException("Invalid announce interval");
        }

        this.announceInterval = interval;
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
    public TrackedPeer update(RequestEvent event, Peer peer,
            long uploaded, long downloaded, long left) throws UnsupportedEncodingException {
        TrackedPeerState state = TrackedPeerState.UNKNOWN;

        TrackedPeer trackedPeer;
        if (RequestEvent.STARTED.equals(event)) {
            trackedPeer = new TrackedPeer(peer, torrent);
            state = TrackedPeerState.STARTED;
            this.addPeer(trackedPeer);
        } else if (RequestEvent.STOPPED.equals(event)) {
            trackedPeer = this.removePeer(peer);
            state = TrackedPeerState.STOPPED;
        } else if (RequestEvent.COMPLETED.equals(event)) {
            trackedPeer = this.getPeer(peer);
            state = TrackedPeerState.COMPLETED;
        } else if (RequestEvent.NONE.equals(event)) {
            trackedPeer = this.getPeer(peer);
            // TODO: There is a chance this will change COMPLETED -> STARTED
            state = TrackedPeerState.STARTED;
        } else {
            throw new IllegalArgumentException("Unexpected announce event type!");
        }

        trackedPeer.update(state, uploaded, downloaded, left);
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
    public List<Peer> getSomePeers(TrackedPeer client, int numWant) {
        numWant = Math.min(numWant, DEFAULT_ANSWER_NUM_PEERS);
        List<Peer> out = new ArrayList<Peer>(numWant);

        // Extract answerPeers random peers
        List<TrackedPeer> candidates = new ArrayList<TrackedPeer>(this.peers.values());
        Collections.shuffle(candidates);

        long now = System.currentTimeMillis();
        for (TrackedPeer candidate : candidates) {
            // Collect unfresh peers, and obviously don't serve them as well.
            if (!candidate.isFresh(now)) {
                logger.debug("Collecting stale peer {}...", candidate.getPeer());
                this.peers.remove(candidate.getPeer());
                continue;
            }

            // Don't include the requesting peer in the answer.
            if (client.getPeer().matches(candidate.getPeer())) {
                if (!client.equals(candidate)) {
                    logger.debug("Collecting superceded peer {}...", candidate);
                    removePeer(candidate.getPeer());
                }
                continue;
            }

            out.add(candidate.getPeer());
            if (out.size() >= numWant)
                break;
        }

        return out;
    }
}