/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.tracker;

import com.codahale.metrics.Gauge;
import com.turn.ttorrent.protocol.torrent.Torrent;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;
import javax.annotation.concurrent.GuardedBy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author shevek
 */
public class TrackedTorrentRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(TrackedTorrentRegistry.class);
    private static final int PEER_COLLECTION_FREQUENCY_SECONDS = 15;
    /** The in-memory repository of torrents tracked. */
    private final ConcurrentMap<String, TrackedTorrent> torrents = new ConcurrentHashMap<String, TrackedTorrent>();
    @GuardedBy("lock")
    private ScheduledExecutorService scheduler;
    private final Object lock = new Object();

    /**
     * Returns the list of tracker's torrents
     */
    @Nonnull
    public Collection<? extends TrackedTorrent> getTorrents() {
        return torrents.values();
    }

    @CheckForNull
    public TrackedTorrent getTorrent(String infoHash) {
        return torrents.get(infoHash);
    }

    @Nonnegative
    public int size() {
        return getTorrents().size();
    }

    /**
     * Start the maintenance threads.
     */
    public void start(TrackerMetrics metrics) {

        synchronized (lock) {
            if (this.scheduler == null || this.scheduler.isShutdown()) {
                // TODO: Set a thread timeout, nothing is time critical.
                this.scheduler = new ScheduledThreadPoolExecutor(1);
                this.scheduler.scheduleWithFixedDelay(new PeerCollector(),
                        PEER_COLLECTION_FREQUENCY_SECONDS,
                        PEER_COLLECTION_FREQUENCY_SECONDS,
                        TimeUnit.SECONDS);
            }

            metrics.addGauge("torrentCount", new Gauge<Integer>() {
                @Override
                public Integer getValue() {
                    return torrents.size();
                }
            });

        }
    }

    /**
     * Stops the maintenance threads and cleans up.
     */
    public void stop() {
        synchronized (lock) {
            if (this.scheduler != null) {
                this.scheduler.shutdownNow();
                this.scheduler = null;
            }
        }
    }

    /**
     * Announce a new torrent on this tracker.
     *
     * <p>
     * The fact that torrents must be announced here first makes this tracker a
     * closed BitTorrent tracker: it will only accept clients for torrents it
     * knows about, and this list of torrents is managed by the program
     * instrumenting this Tracker class.
     * </p>
     *
     * @param torrent The Torrent object to start tracking.
     * @return The torrent object for this torrent on this tracker. This may be
     * different from the supplied Torrent object if the tracker already
     * contained a torrent with the same hash.
     */
    @Nonnull
    public synchronized TrackedTorrent announce(@Nonnull TrackedTorrent torrent) {
        TrackedTorrent existing = this.torrents.get(torrent.getHexInfoHash());

        if (existing != null) {
            LOG.warn("Tracker already announced torrent for '{}' "
                    + "with hash {}.", existing.getName(), existing.getHexInfoHash());
            return existing;
        }

        this.torrents.put(torrent.getHexInfoHash(), torrent);
        LOG.info("Registered new torrent for '{}' with hash {}.",
                torrent.getName(), torrent.getHexInfoHash());
        return torrent;
    }

    @Nonnull
    public TrackedTorrent announce(@Nonnull Torrent torrent) {
        return announce(new TrackedTorrent(torrent));
    }

    /**
     * Stop announcing the given torrent.
     *
     * @param torrent The Torrent object to stop tracking.
     */
    public void remove(@CheckForNull Torrent torrent) {
        if (torrent == null)
            return;

        this.torrents.remove(torrent.getHexInfoHash());
    }

    /**
     * Stop announcing the given torrent after a delay.
     *
     * @param torrent The Torrent object to stop tracking.
     * @param delay The delay, in milliseconds, before removing the torrent.
     */
    public void remove(Torrent torrent, long delay, TimeUnit unit) {
        if (torrent == null)
            return;

        synchronized (lock) {
            if (scheduler != null)
                scheduler.schedule(new TorrentRemover(torrent), delay, unit);
            else
                remove(torrent);
        }
    }

    /**
     * Runnable for removing a torrent from a tracker.
     *
     * <p>
     * This task can be used to stop announcing a torrent after a certain delay.
     * </p>
     */
    private class TorrentRemover implements Runnable {

        private final Torrent torrent;

        TorrentRemover(@Nonnull Torrent torrent) {
            this.torrent = torrent;
        }

        @Override
        public void run() {
            remove(torrent);
        }
    }

    /**
     * The unfresh peer collector.
     *
     * <p>
     * Every PEER_COLLECTION_FREQUENCY_SECONDS, this runnable will collect
     * unfresh peers from all announced torrents.
     * </p>
     */
    private class PeerCollector implements Runnable {

        @Override
        public void run() {
            int count = 0;
            for (TrackedTorrent torrent : torrents.values()) {
                count += torrent.collectUnfreshPeers();
            }
            if (count > 0)
                LOG.debug("Collected {} stale peers.", count);
        }
    }
}
