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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Objects;
import com.turn.ttorrent.client.tracker.AnnounceResponseListener;
import com.turn.ttorrent.client.tracker.TrackerClient;

import com.turn.ttorrent.common.protocol.TrackerMessage;

import java.net.SocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;


import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.annotation.CheckForNull;
import javax.annotation.CheckForSigned;
import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * BitTorrent announce sub-system.
 *
 * <p>
 * A BitTorrent client must check-in to the torrent's tracker(s) to get peers
 * and to report certain events.
 * </p>
 *
 * <p>
 * This Announce class implements a periodic announce request thread that will
 * notify announce request event listeners for each tracker response.
 * </p>
 *
 * @author mpetazzoni
 * @see com.turn.ttorrent.common.protocol.TrackerMessage
 */
public class TrackerHandler implements Runnable, AnnounceResponseListener {

    private static final Logger LOG = LoggerFactory.getLogger(TrackerHandler.class);

    public static final long DELAY_DEFAULT = 5000;
    public static final long DELAY_MIN = 500;
    public static final long DELAY_RESCHEDULE_DELTA = 100;

    @VisibleForTesting
    /* pp */ static class TrackerState {

        private final URI uri;
        private final int tier;
        /** Epoch. */
        private long lastSend;
        /** Epoch. */
        private long lastRecv;
        private long lastErr;
        /** Milliseconds, although protocol is in seconds. */
        private long interval = DELAY_DEFAULT;

        public TrackerState(@Nonnull URI uri, int tier) {
            this.uri = uri;
            this.tier = tier;
        }

        @Nonnull
        public URI getUri() {
            return uri;
        }

        /** In seconds. */
        public void setInterval(long interval) {
            this.interval = interval;
        }

        @CheckForSigned
        public long getDelay() {
            long last = Math.max(lastSend, lastRecv);
            long then = last + interval;
            return then - System.currentTimeMillis();
        }

        @Nonnegative
        public long getRescheduleDelay() {
            return Math.max(getDelay(), DELAY_MIN); // Could use 0 here.
        }

        @Override
        public String toString() {
            return uri + " (tier=" + tier + ", interval=" + interval + ")";
        }
    }
    private final Client client;
    private final TorrentMetadataProvider torrent;
    private final List<TrackerState> trackers = new ArrayList<TrackerState>();
    private int trackerIndex;
    private TrackerMessage.AnnounceEvent event = TrackerMessage.AnnounceEvent.STARTED;
    private ScheduledFuture<?> future;
    private final Object lock = new Object();

    /**
     * Initialize the base announce class members for the announcer.
     *
     * @param torrent The torrent we're announcing about.
     * @param peer Our peer specification.
     */
    public TrackerHandler(Client client, TorrentMetadataProvider torrent) {
        this.client = client;
        this.torrent = torrent;
    }

    @Nonnull
    private Client getClient() {
        return client;
    }

    @Nonnull
    private ScheduledExecutorService getSchedulerService() {
        return getClient().getEnvironment().getSchedulerService();
    }

    /**
     * Locate a {@link TrackerClient} announcing to the given tracker address.
     *
     * @param tracker The tracker address as a {@link URI}.
     */
    @VisibleForTesting
    /* pp */ TrackerClient getTrackerClient(@Nonnull URI tracker) {
        String scheme = tracker.getScheme();
        // LOG.trace("Tracker scheme is " + scheme);
        if ("http".equals(scheme) || "https".equals(scheme)) {
            // LOG.trace("Looking for HttpTrackerClient");
            return getClient().getHttpTrackerClient();
            // } else if ("udp".equals(scheme)) {
            // TODO: Check we have an ipv4 address before allowing the UDP protocol.
            // return getClient().getUdpTrackerClient();
        } else {
            return null;
        }
    }

    @CheckForNull
    @VisibleForTesting
    /* pp */ TrackerState getTracker(@Nonnull URI uri) {
        // Needs synchronization against the swap() in promoteCurrentTracker()
        synchronized (lock) {
            {
                TrackerState tracker = getCurrentTracker();
                if (tracker.uri.equals(uri))
                    return tracker;
            }
            for (TrackerState tracker : trackers)
                if (tracker.uri.equals(uri))
                    return tracker;
        }
        LOG.warn("No tracker for {}: available are {}", uri, trackers);
        return null;
    }

    /**
     * Returns the current tracker client used for announces.
     */
    @Nonnull
    @VisibleForTesting
    /* pp */ TrackerState getCurrentTracker() {
        synchronized (lock) {
            return trackers.get(trackerIndex);
        }
    }

    /**
     * Promotes the current tracker to the head of its tier.
     *
     * As defined by BEP#0012, when communication with a tracker is successful,
     * it should be moved to the front of its tier.
     */
    @VisibleForTesting
    /* pp */ void promoteCurrentTracker() {
        synchronized (lock) {
            int currentTier = trackers.get(trackerIndex).tier;
            int idx;
            for (idx = trackerIndex - 1; idx >= 0; idx--) {
                if (trackers.get(idx).tier != currentTier) {
                    idx++;
                    break;
                }
            }
            Collections.swap(trackers, trackerIndex, idx);
            trackerIndex = idx;
        }
    }

    /**
     * Move to the next tracker client.
     *
     * <p>
     * If no more trackers are available in the current tier, move to the next
     * tier. If we were on the last tier, restart from the first tier.
     * </p>
     */
    @VisibleForTesting
    /* pp */ boolean moveToNextTracker(@Nonnull TrackerState curr, @Nonnull String reason) {
        TrackerState prev, next;
        synchronized (lock) {
            prev = getCurrentTracker();
            if (curr != prev)
                return false;
            if (++trackerIndex >= trackers.size())
                trackerIndex = 0;
            next = getCurrentTracker();
        }
        LOG.info("Moved tracker: {} -> {}: {}",
                new Object[]{
            prev, next, reason
        });
        if (LOG.isDebugEnabled())
            LOG.debug("{}", this);
        return true;
    }

    /********** Event drivers *****/
    public void start() {
        if (LOG.isDebugEnabled())
            LOG.debug("Starting TrackerHandler for {}", torrent);
        synchronized (lock) {

            int tier = 0;
            for (List<? extends URI> announceTier : torrent.getAnnounceList()) {
                if (LOG.isTraceEnabled())
                    LOG.trace("Loading tier {}", announceTier);
                for (URI announceUri : announceTier) {
                    if (LOG.isTraceEnabled())
                        LOG.trace("Loading client for {}", announceUri);
                    if (getTrackerClient(announceUri) != null) {
                        trackers.add(new TrackerState(announceUri, tier));
                    } else {
                        LOG.warn("No tracker client available for {}.", announceUri);
                    }
                }
                tier++;
            }

            LOG.info("Initialized announce sub-system with {} trackers on {}.",
                    new Object[]{trackers.size(), torrent});

            event = TrackerMessage.AnnounceEvent.STARTED;
            if (LOG.isDebugEnabled())
                LOG.debug("Started TrackerHandler {}", this);
            run();
        }
    }

    public void complete() {
        synchronized (lock) {
            event = TrackerMessage.AnnounceEvent.COMPLETED;
            // run();
        }
    }

    public void stop() {
        LOG.info("Stopping TrackerHandler for {}", torrent);
        synchronized (lock) {
            event = TrackerMessage.AnnounceEvent.STOPPED;
            if (future != null)
                future.cancel(false);
            run();
            trackers.clear();
        }
    }

    private void reschedule(@Nonnegative long requestedDelay) {
        // LOG.trace("Rescheduling tracker for {}", delay);
        synchronized (lock) {
            if (event == TrackerMessage.AnnounceEvent.STOPPED)
                return;

            if (future != null) {
                long actualDelay = future.getDelay(TimeUnit.MILLISECONDS);
                long delta = actualDelay - requestedDelay;
                // Don't reschedule if it's "soon".
                if (LOG.isTraceEnabled())
                    LOG.trace("Reschedule: requested={}, actual={}, delta={}", new Object[]{
                        requestedDelay, actualDelay, delta
                    });
                if (actualDelay > 0)
                    if (Math.abs(delta) < DELAY_RESCHEDULE_DELTA)
                        return;
                future.cancel(false);
            }

            if (requestedDelay < DELAY_MIN)
                requestedDelay = DELAY_MIN;
            future = getSchedulerService().schedule(this, requestedDelay, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Main announce loop.
     *
     * <p>
     * The announce thread starts by making the initial 'started' announce
     * request to register on the tracker and get the announce interval value.
     * Subsequent announce requests are ordinary, event-less, periodic requests
     * for peers.
     * </p>
     *
     * <p>
     * Unless forcefully stopped, the announce thread will terminate by sending
     * a 'stopped' announce request before stopping.
     * </p>
     */
    @Override
    public void run() {
        TrackerState tracker;
        synchronized (lock) {
            tracker = getCurrentTracker();
        }
        try {
            TrackerClient client = getTrackerClient(tracker.uri);
            client.announce(this, torrent, tracker.uri, event, false);
            synchronized (lock) {
                tracker.lastSend = System.currentTimeMillis();
            }
        } catch (Exception e) {
            LOG.error("Failed to announce to " + tracker, e);
            moveToNextTracker(tracker, "Announce threw " + e);
        } finally {
            reschedule(tracker.getRescheduleDelay());
        }
    }

    /** AnnounceResponseListener handler(s). **********************************/
    /**
     * Handle an announce response event.
     *
     * @param interval The announce interval requested by the tracker.
     * @param complete The number of seeders on this torrent.
     * @param incomplete The number of leechers on this torrent.
     */
    @Override
    public void handleAnnounceResponse(URI uri, long interval, int complete, int incomplete) {
        synchronized (lock) {
            TrackerState tracker = getTracker(uri);
            if (tracker == null)
                return;
            tracker.lastRecv = System.currentTimeMillis();
            tracker.setInterval(interval);
            reschedule(tracker.getRescheduleDelay());
        }
    }

    @Override
    public void handleAnnounceFailed(URI uri, String reason) {
        synchronized (lock) {
            if (event == TrackerMessage.AnnounceEvent.STOPPED)
                return;
            TrackerState tracker = getTracker(uri);
            if (tracker == null)
                return;
            tracker.lastErr = System.currentTimeMillis();
            moveToNextTracker(tracker, "Announce failed to " + uri + ": " + reason);
            reschedule(getCurrentTracker().getRescheduleDelay());
        }
    }

    /**
     * Handle the discovery of new peers.
     *
     * @param peers The list of peers discovered (from the announce response or
     * any other means like DHT/PEX, etc.).
     */
    @Override
    public void handleDiscoveredPeers(URI uri, Collection<? extends SocketAddress> peerAddresses) {
        synchronized (lock) {
            TrackerState tracker = getTracker(uri);
            if (tracker == null)
                return;
            tracker.lastRecv = System.currentTimeMillis();
        }

        // LOG.trace("Got {} peer(s) in tracker response.", peerAddresses.size());
        // torrent.getSwarmHandler().getOrCreatePeer(null, remotePeerId);

        torrent.addPeers(peerAddresses);
        // torrent.getSwarmHandler().addPeers(peerAddresses);
    }

    @Override
    public String toString() {
        synchronized (lock) {
            return Objects.toStringHelper(this)
                    .add("trackers", trackers)
                    .add("trackerIndex", trackerIndex)
                    .add("event", event)
                    .toString();
        }
    }
}