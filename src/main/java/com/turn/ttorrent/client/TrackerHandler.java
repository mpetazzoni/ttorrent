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
import com.turn.ttorrent.client.announce.AnnounceResponseListener;
import com.turn.ttorrent.client.announce.TrackerClient;
import com.turn.ttorrent.common.Peer;
import com.turn.ttorrent.common.protocol.TrackerMessage.*;

import java.net.URI;
import java.util.ArrayList;
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

    protected static final Logger logger = LoggerFactory.getLogger(TrackerHandler.class);

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
        private long interval;

        public TrackerState(@Nonnull URI uri, int tier) {
            this.uri = uri;
            this.tier = tier;
            setInterval(5);
        }

        /** In seconds. */
        public void setInterval(int interval) {
            this.interval = TimeUnit.SECONDS.toMillis(interval);
        }

        @CheckForSigned
        public long getDelay() {
            long last = Math.max(lastSend, lastRecv);
            long then = last + interval;
            return then - System.currentTimeMillis();
        }

        @Override
        public String toString() {
            return uri + " [T" + tier + "]";
        }
    }
    private final SharedTorrent torrent;
    private final List<TrackerState> trackers = new ArrayList<TrackerState>();
    private int currentClient;
    private AnnounceRequestMessage.RequestEvent event = AnnounceRequestMessage.RequestEvent.STARTED;
    private ScheduledFuture<?> future;
    private final Object lock = new Object();

    /**
     * Initialize the base announce class members for the announcer.
     *
     * @param torrent The torrent we're announcing about.
     * @param peer Our peer specification.
     */
    public TrackerHandler(SharedTorrent torrent) {
        this.torrent = torrent;

        int tier = 0;
        for (List<URI> announceTier : torrent.getTorrent().getAnnounceList()) {
            for (URI announceUri : announceTier) {
                if (getTrackerClient(announceUri) != null) {
                    trackers.add(new TrackerState(announceUri, tier));
                } else {
                    logger.warn("No tracker client available for {}.", announceUri);
                }
            }
            tier++;
        }

        logger.info("Initialized announce sub-system with {} trackers on {}.",
                new Object[]{trackers.size(), torrent});
    }

    @Nonnull
    public Client getClient() {
        return torrent.getClient();
    }

    @Nonnull
    public ScheduledExecutorService getSchedulerService() {
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
        if ("http".equals(scheme) || "https".equals(scheme)) {
            return getClient().getHttpTrackerClient();
        // } else if ("udp".equals(scheme)) {
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
        logger.warn("No tracker for {}: available are {}", uri, trackers);
        return null;
    }

    /**
     * Returns the current tracker client used for announces.
     */
    @Nonnull
    @VisibleForTesting
    /* pp */ TrackerState getCurrentTracker() {
        synchronized (lock) {
            return trackers.get(currentClient);
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
            int currentTier = trackers.get(currentClient).tier;
            int idx;
            for (idx = currentClient - 1; idx >= 0; idx--) {
                if (trackers.get(idx).tier != currentTier) {
                    idx++;
                    break;
                }
            }
            Collections.swap(trackers, currentClient, idx);
            currentClient = idx;
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
    /* pp */ void moveToNextTracker() {
        synchronized (lock) {
            if (++currentClient >= trackers.size())
                currentClient = 0;
        }
    }

    /********** Event drivers *****/
    public void start() {
        logger.info("Starting TrackerHandler for {}", torrent);
        synchronized (lock) {
            event = AnnounceRequestMessage.RequestEvent.STARTED;
            run();
        }
    }

    public void complete() {
        synchronized (lock) {
            event = AnnounceRequestMessage.RequestEvent.COMPLETED;
            run();
        }
    }

    public void stop() {
        logger.info("Stopping TrackerHandler for {}", torrent);
        synchronized (lock) {
            event = AnnounceRequestMessage.RequestEvent.STOPPED;
            future.cancel(false);
            run();
        }
    }

    private void reschedule(@Nonnegative long delay) {
        synchronized (lock) {
            if (event != AnnounceRequestMessage.RequestEvent.STOPPED)
                return;

            long delta = future.getDelay(TimeUnit.MILLISECONDS) - delay;
            // Don't reschedule if it's "soon".
            if (delta > 10 && delta < 100)
                return;
            future.cancel(false);
            future = getSchedulerService().schedule(this, delay, TimeUnit.MILLISECONDS);
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
        synchronized (lock) {
            TrackerState tracker = getCurrentTracker();
            try {
                TrackerClient client = getTrackerClient(tracker.uri);
                client.announce(this, torrent, tracker.uri, event, false);
                tracker.lastSend = System.currentTimeMillis();
            } catch (Exception e) {
                logger.error("Failed to announce to " + tracker, e);
                moveToNextTracker();
            } finally {
                reschedule(tracker.getDelay());
            }
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
    public void handleAnnounceResponse(URI uri, int interval, int complete, int incomplete) {
        synchronized (lock) {
            TrackerState tracker = getTracker(uri);
            if (tracker == null)
                return;
            tracker.lastRecv = System.currentTimeMillis();
            tracker.setInterval(interval);
            reschedule(tracker.getDelay());
        }
    }

    @Override
    public void handleAnnounceFailed(URI uri) {
        synchronized (lock) {
            if (event == AnnounceRequestMessage.RequestEvent.STOPPED)
                return;
            TrackerState tracker = getTracker(uri);
            if (tracker == null)
                return;
            tracker.lastErr = System.currentTimeMillis();
            if (tracker == getCurrentTracker()) {
                moveToNextTracker();
                run();
            }
        }
    }

    /**
     * Handle the discovery of new peers.
     *
     * @param peers The list of peers discovered (from the announce response or
     * any other means like DHT/PEX, etc.).
     */
    @Override
    public void handleDiscoveredPeers(URI uri, List<Peer> peers) {
        synchronized (lock) {
            TrackerState tracker = getTracker(uri);
            if (tracker == null)
                return;
            tracker.lastRecv = System.currentTimeMillis();
        }

        if (peers == null || peers.isEmpty()) {
            // No peers returned by the tracker. Apparently we're alone on
            // this one for now.
            return;
        }

        logger.info("Got {} peer(s) in tracker response.", peers.size());

        /*
         for (Peer peer : peers) {
         // Attempt to connect to the peer if and only if:
         //   - We're not already connected or connecting to it;
         //   - We're not a seeder (we leave the responsibility
         //	   of connecting to peers that need to download
         //     something).
         SharingPeer match = this.getOrCreatePeer(peer);
         if (this.isSeed()) {
         continue;
         }

         synchronized (match) {
         if (!match.isConnected()) {
         connect(match);
         }
         }
         }
         */
    }
}