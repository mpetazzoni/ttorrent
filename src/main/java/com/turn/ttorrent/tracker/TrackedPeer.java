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

import com.turn.ttorrent.bcodec.BEValue;
import com.turn.ttorrent.common.Peer;
import com.turn.ttorrent.common.Torrent;

import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A BitTorrent tracker peer.
 *
 * <p>
 * Represents a peer exchanging on a given torrent. In this implementation,
 * we don't really care about the status of the peers and how much they
 * have downloaded / exchanged because we are not a torrent exchange and
 * don't need to keep track of what peers are doing while they're
 * downloading. We only care about when they start, and when they are done.
 * </p>
 *
 * <p>
 * We also never expire peers automatically. Unless peers send a STOPPED
 * announce request, they remain as long as the torrent object they are a
 * part of.
 * </p>
 */
public class TrackedPeer {

    private static final Logger logger =
            LoggerFactory.getLogger(TrackedPeer.class);
    private static final int FRESH_TIME_SECONDS = 30;
    private final Peer peer;
    // TODO: The only reason to keep this reference here is to produce debug logs.
    private long uploaded;
    private long downloaded;
    private long left;
    private TrackedPeerState state;
    private long lastAnnounce = System.currentTimeMillis();
    // We need a happen-before relationship for multiple threads.
    private final Object lock = new Object();

    /**
     * Instantiate a new tracked peer for the given torrent.
     *
     * @param peer The remote peer.
     * @param torrent The torrent this peer exchanges on.
     */
    public TrackedPeer(Peer peer) {
        this.peer = peer;

        // Instantiated peers start in the UNKNOWN state.
        this.state = TrackedPeerState.UNKNOWN;

        this.uploaded = 0;
        this.downloaded = 0;
        this.left = 0;
    }

    @Nonnull
    public Peer getPeer() {
        return peer;
    }

    /**
     * Update this peer's state and information.
     *
     * <p>
     * <b>Note:</b> if the peer reports 0 bytes left to download, its state will
     * be automatically be set to COMPLETED.
     * </p>
     *
     * @param torrent The torrent. This should be the same on every call.
     * @param state The peer's state.
     * @param uploaded Uploaded byte count, as reported by the peer.
     * @param downloaded Downloaded byte count, as reported by the peer.
     * @param left Left-to-download byte count, as reported by the peer.
     */
    public void update(@Nonnull TrackedTorrent torrent, TrackedPeerState state, long uploaded, long downloaded, long left) {
        if (TrackedPeerState.STARTED.equals(state) && left == 0)
            state = TrackedPeerState.COMPLETED;

        if (!state.equals(this.state)) {
            logger.info("Peer {} {} download of {}.",
                    new Object[]{
                this,
                state.name().toLowerCase(),
                torrent
            });
        }

        synchronized (lock) {
            this.state = state;
            this.lastAnnounce = System.currentTimeMillis();
            this.uploaded = uploaded;
            this.downloaded = downloaded;
            this.left = left;
        }
    }

    /**
     * Tells whether this peer has completed its download and can thus be
     * considered a seeder.
     */
    public boolean isCompleted() {
        return TrackedPeerState.COMPLETED.equals(this.state);
    }

    /**
     * Returns how many bytes the peer reported it has uploaded so far.
     */
    public long getUploaded() {
        return this.uploaded;
    }

    /**
     * Returns how many bytes the peer reported it has downloaded so far.
     */
    public long getDownloaded() {
        return this.downloaded;
    }

    /**
     * Returns how many bytes the peer reported it needs to retrieve before
     * its download is complete.
     */
    public long getLeft() {
        return this.left;
    }

    /**
     * Tells whether this peer has checked in with the tracker recently.
     *
     * <p>
     * Non-fresh peers are automatically terminated and collected by the
     * Tracker.
     * </p>
     */
    public boolean isFresh(long now) {
        // TODO: Shouldn't this use the announce interval from the tracked torrent?
        synchronized (lock) {
            return (this.lastAnnounce > 0
                    && (this.lastAnnounce + (FRESH_TIME_SECONDS * 1000) > now));
        }
    }

    /**
     * Returns a BEValue representing this peer for inclusion in an
     * announce reply from the tracker.
     *
     * The returned BEValue is a dictionary containing the peer ID (in its
     * original byte-encoded form), the peer's IP and the peer's port.
     */
    public BEValue toBEValue() throws UnsupportedEncodingException {
        Map<String, BEValue> out = new HashMap<String, BEValue>();
        if (peer.hasPeerId()) {
            out.put("peer id", new BEValue(peer.getPeerId()));
        }
        out.put("ip", new BEValue(peer.getIp(), Torrent.BYTE_ENCODING));
        out.put("port", new BEValue(peer.getPort()));
        return new BEValue(out);
    }
}
