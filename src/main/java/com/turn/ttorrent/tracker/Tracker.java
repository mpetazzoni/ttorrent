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

import com.google.common.collect.Iterables;
import com.google.common.net.InetAddresses;
import com.turn.ttorrent.common.Torrent;

import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.Gauge;
import com.yammer.metrics.core.MetricsRegistry;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.NetworkInterface;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.annotation.CheckForSigned;
import javax.annotation.Nonnull;
import org.simpleframework.transport.connect.Connection;
import org.simpleframework.transport.connect.SocketConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * BitTorrent tracker.
 *
 * <p>
 * The tracker usually listens on port 6969 (the standard BitTorrent tracker
 * port). Torrents must be registered directly to this tracker with the
 * {@link #announce(TrackedTorrent torrent)}</code> method.
 * </p>
 *
 * @author mpetazzoni
 */
public class Tracker {

    private static final Logger LOG = LoggerFactory.getLogger(Tracker.class);
    /** Request path handled by the tracker announce request handler. */
    public static final String ANNOUNCE_URL = "/announce";
    /** Default tracker listening port (BitTorrent's default is 6969). */
    public static final int DEFAULT_TRACKER_PORT = 6969;
    /** Default server name and version announced by the tracker. */
    public static final String DEFAULT_VERSION_STRING = "BitTorrent Tracker (ttorrent)";
    private static final int PEER_COLLECTION_FREQUENCY_SECONDS = 15;

    public static class Listener {

        private Connection connection;
        private SocketAddress connectionAddress;
    }
    private final String version;
    private MetricsRegistry metricsRegistry = Metrics.defaultRegistry();
    private final ConcurrentMap<InetSocketAddress, Listener> listeners = new ConcurrentHashMap<InetSocketAddress, Listener>();
    /** The in-memory repository of torrents tracked. */
    private final ConcurrentMap<String, TrackedTorrent> torrents = new ConcurrentHashMap<String, TrackedTorrent>();
    private TrackerMetrics metrics;
    private ScheduledExecutorService scheduler;
    private final Object lock = new Object();

    public Tracker() {
        this(DEFAULT_VERSION_STRING);
    }

    /**
     * Create a new BitTorrent tracker listening at the given address.
     *
     * You will want to call {@link #addAddress(InetSocketAddress)}
     * to add listen addresses.
     */
    public Tracker(String version) {
        this.version = version;
    }

    /**
     * Create a new BitTorrent tracker listening at the given address.
     *
     * @param address The address to bind to.
     * @throws IOException Throws an <em>IOException</em> if the tracker
     * cannot be initialized.
     */
    public Tracker(@Nonnull InetSocketAddress address) throws IOException {
        this();
        addListenAddress(address);
    }

    /**
     * Create a new BitTorrent tracker listening at the given address on the
     * default port.
     *
     * @param address The address to bind to.
     * @throws IOException Throws an <em>IOException</em> if the tracker
     * cannot be initialized.
     */
    public Tracker(@Nonnull InetAddress address) throws IOException {
        this(new InetSocketAddress(address, DEFAULT_TRACKER_PORT));
    }

    /** Call this BEFORE you start the tracker. */
    // TODO: As per Client, allow adding after tracker is started.
    public void addListenAddress(@Nonnull InetSocketAddress address) {
        listeners.put(address, new Listener());
    }

    public void addListenInterface(@Nonnull NetworkInterface iface, @CheckForSigned int port) {
        for (InetAddress ifaddr : Collections.list(iface.getInetAddresses())) {
            addListenAddress(new InetSocketAddress(ifaddr, port));
        }
    }

    private void add(@Nonnull Set<? super InetSocketAddress> out, @Nonnull InetSocketAddress in) throws SocketException {
        // LOG.info("Looking for addresses from " + in);
        InetAddress addr = in.getAddress();
        if (addr.isAnyLocalAddress()) {
            for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (iface.isLoopback())
                    continue;
                if (!iface.isUp())
                    continue;
                for (InetAddress ifaddr : Collections.list(iface.getInetAddresses())) {
                    // LOG.info("ifaddr=" + ifaddr + " iftype=" + ifaddr.getClass() + " atype=" + addr.getClass());
                    if (ifaddr.isLoopbackAddress())
                        continue;
                    // If we prefer the IPv6 stack, then addr.getClass() is Inet6Address
                    // if (!ifaddr.getClass().equals(addr.getClass())) continue;
                    out.add(new InetSocketAddress(ifaddr, in.getPort()));
                }
            }
        } else {
            out.add(in);
        }
    }

    @Nonnull
    public Iterable<? extends InetSocketAddress> getListenAddresses() throws SocketException {
        Set<InetSocketAddress> out = new HashSet<InetSocketAddress>();
        for (Map.Entry<InetSocketAddress, Listener> e : listeners.entrySet()) {
            SocketAddress a = e.getValue().connectionAddress;   // In case we bound ephemerally.
            if (a instanceof InetSocketAddress)         // Also covers == null.
                add(out, (InetSocketAddress) a);
            else
                add(out, e.getKey());
        }
        return out;
    }

    /**
     * Returns the full announce URL served by this tracker.
     *
     * <p>
     * This has the form http://ip:port/announce.
     * </p>
     */
    @Nonnull
    public List<URL> getAnnounceUrls() throws SocketException {
        List<URL> out = new ArrayList<URL>();
        for (InetSocketAddress address : getListenAddresses()) {
            try {
                out.add(new URL("http",
                        InetAddresses.toUriString(address.getAddress()),
                        address.getPort(),
                        Tracker.ANNOUNCE_URL));
            } catch (MalformedURLException e) {
                LOG.error("Could not build tracker URL from " + address, e);
            }
        }
        return out;
    }

    @Nonnull
    public List<URI> getAnnounceUris() throws SocketException {
        List<URI> out = new ArrayList<URI>();
        for (URL url : getAnnounceUrls()) {
            try {
                out.add(url.toURI());
            } catch (URISyntaxException e) {
                LOG.error("Could not build tracker URI from " + url, e);
            }
        }
        return out;
    }

    @Nonnull
    public MetricsRegistry getMetricsRegistry() {
        return metricsRegistry;
    }

    public void setMetricsRegistry(@Nonnull MetricsRegistry metricsRegistry) {
        this.metricsRegistry = metricsRegistry;
    }

    /**
     * Start the tracker thread.
     */
    public void start() throws IOException {
        LOG.info("Starting BitTorrent tracker on {}...", getAnnounceUrls());
        synchronized (lock) {

            if (this.metrics == null) {
                Object trackerId = Iterables.getFirst(getListenAddresses(), System.identityHashCode(this));
                this.metrics = new TrackerMetrics(getMetricsRegistry(), trackerId);

                metrics.addGauge("torrentCount", new Gauge<Integer>() {
                    @Override
                    public Integer value() {
                        return torrents.size();
                    }
                });
            }

            for (Map.Entry<InetSocketAddress, Listener> e : listeners.entrySet()) {
                Listener listener = e.getValue();
                if (listener.connection == null) {
                    // Creates a thread via: SocketConnection
                    // -> ListenerManager -> Listener
                    // -> DirectReactor -> ActionDistributor -> Daemon
                    listener.connection = new SocketConnection(new TrackerService(version, torrents, metrics));
                    listener.connectionAddress = listener.connection.connect(e.getKey());
                }
            }

            if (this.scheduler == null || this.scheduler.isShutdown()) {
                // TODO: Set a thread timeout, nothing is time critical.
                this.scheduler = new ScheduledThreadPoolExecutor(1);
                this.scheduler.scheduleWithFixedDelay(new PeerCollector(),
                        PEER_COLLECTION_FREQUENCY_SECONDS,
                        PEER_COLLECTION_FREQUENCY_SECONDS,
                        TimeUnit.SECONDS);
            }

        }
        LOG.info("Started BitTorrent tracker on {}...", getAnnounceUrls());
    }

    /**
     * Stop the tracker.
     *
     * <p>
     * This effectively closes the listening HTTP connection to terminate
     * the service, and interrupts the peer collector thread as well.
     * </p>
     */
    public void stop() throws IOException {
        LOG.info("Stopping BitTorrent tracker on {}...", getAnnounceUrls());
        synchronized (lock) {

            if (this.metrics != null) {
                this.metrics.shutdown();
                this.metrics = null;
            }

            for (Map.Entry<InetSocketAddress, Listener> e : listeners.entrySet()) {
                Listener listener = e.getValue();
                if (listener.connection != null) {
                    listener.connection.close();
                    listener.connection = null;
                }
                listener.connectionAddress = null;
            }

            if (this.scheduler != null) {
                this.scheduler.shutdownNow();
                this.scheduler = null;
            }

        }
        LOG.info("Stopped BitTorrent tracker on {}...", getAnnounceUrls());
    }

    /**
     * Returns the list of tracker's torrents
     */
    public Collection<? extends TrackedTorrent> getTrackedTorrents() {
        return torrents.values();
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
    public synchronized TrackedTorrent announce(TrackedTorrent torrent) {
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
    public TrackedTorrent announce(Torrent torrent) {
        return announce(new TrackedTorrent(torrent));
    }

    /**
     * Stop announcing the given torrent.
     *
     * @param torrent The Torrent object to stop tracking.
     */
    public synchronized void remove(Torrent torrent) {
        if (torrent == null) {
            return;
        }

        this.torrents.remove(torrent.getHexInfoHash());
    }

    /**
     * Stop announcing the given torrent after a delay.
     *
     * @param torrent The Torrent object to stop tracking.
     * @param delay The delay, in milliseconds, before removing the torrent.
     */
    public synchronized void remove(Torrent torrent, long delay) {
        if (torrent == null) {
            return;
        }

        scheduler.schedule(new TorrentRemover(torrent), delay, TimeUnit.SECONDS);
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
