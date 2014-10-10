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
package com.turn.torrent.tracker.simple;

import com.codahale.metrics.MetricRegistry;
import com.google.common.net.InetAddresses;

import com.turn.ttorrent.protocol.TorrentUtils;
import com.turn.ttorrent.protocol.torrent.Torrent;
import com.turn.ttorrent.tracker.TrackedTorrent;
import com.turn.ttorrent.tracker.TrackedTorrentRegistry;
import com.turn.ttorrent.tracker.TrackerMetrics;
import com.turn.ttorrent.tracker.TrackerUtils;
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
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.annotation.CheckForSigned;
import javax.annotation.Nonnull;
import org.simpleframework.http.core.ContainerServer;
import org.simpleframework.transport.Server;
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
 * {@link #addTorrent(TrackedTorrent torrent)}</code> method.
 * </p>
 *
 * @author mpetazzoni
 */
public class SimpleTracker {

    private static final Logger LOG = LoggerFactory.getLogger(SimpleTracker.class);

    public static class Listener {

        private Connection connection;
        private SocketAddress connectionAddress;
    }
    private final SimpleTrackerService service;
    private MetricRegistry metricRegistry = new MetricRegistry();
    private TrackerMetrics metrics;
    private final ConcurrentMap<InetSocketAddress, Listener> listeners = new ConcurrentHashMap<InetSocketAddress, Listener>();
    private final Object lock = new Object();

    /**
     * Create a new BitTorrent tracker listening at the given address.
     *
     * You will want to call {@link #addAddress(InetSocketAddress)}
     * to add listen addresses.
     */
    public SimpleTracker(@Nonnull String version) {
        this.service = new SimpleTrackerService(version);
    }

    public SimpleTracker() {
        this(TrackerUtils.DEFAULT_VERSION_STRING);
    }

    /**
     * Create a new BitTorrent tracker listening at the given address.
     *
     * @param address The address to bind to.
     * @throws IOException Throws an <em>IOException</em> if the tracker
     * cannot be initialized.
     */
    public SimpleTracker(@Nonnull InetSocketAddress address) throws IOException {
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
    public SimpleTracker(@Nonnull InetAddress address) throws IOException {
        this(new InetSocketAddress(address, TrackerUtils.DEFAULT_TRACKER_PORT));
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
        InetAddress inaddr = in.getAddress();
        for (InetAddress address : TorrentUtils.getSpecificAddresses(inaddr))
            out.add(new InetSocketAddress(address, in.getPort()));
    }

    @Nonnull
    public Iterable<? extends InetSocketAddress> getListenAddresses() throws SocketException {
        Set<InetSocketAddress> out = new HashSet<InetSocketAddress>();
        for (Map.Entry<InetSocketAddress, Listener> e : listeners.entrySet()) {
            SocketAddress a = e.getValue().connectionAddress;   // In case we bound ephemerally.
            if (a instanceof InetSocketAddress)         // Also ensures != null.
                add(out, (InetSocketAddress) a);
            else
                add(out, e.getKey());
        }
        return out;
    }

    @Nonnull
    public TrackedTorrentRegistry getTorrents() {
        return service.getTorrents();
    }

    @Nonnull
    public TrackedTorrent addTorrent(@Nonnull TrackedTorrent torrent) {
        return getTorrents().announce(torrent);
    }

    @Nonnull
    public TrackedTorrent addTorrent(@Nonnull Torrent torrent) {
        return getTorrents().announce(torrent);
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
                        TrackerUtils.DEFAULT_ANNOUNCE_URL));
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
    public MetricRegistry getMetricRegistry() {
        return metricRegistry;
    }

    public void setMetricRegistry(@Nonnull MetricRegistry metricRegistry) {
        this.metricRegistry = metricRegistry;
    }

    /**
     * Start the tracker thread.
     */
    public void start() throws IOException {
        LOG.info("Starting tracker service on {}", getAnnounceUris());
        synchronized (lock) {
            if (this.metrics == null)
                this.metrics = new TrackerMetrics(getMetricRegistry(), String.valueOf(System.identityHashCode(this)));
            this.service.start(metrics);

            for (Map.Entry<? extends InetSocketAddress, ? extends Listener> e : listeners.entrySet()) {
                Listener listener = e.getValue();
                if (listener.connection == null) {
                    // Creates a thread via: SocketConnection
                    // -> ListenerManager -> Listener
                    // -> DirectReactor -> ActionDistributor -> Daemon
                    Server server = new ContainerServer(service);
                    listener.connection = new SocketConnection(server);
                    listener.connectionAddress = listener.connection.connect(e.getKey());
                }
            }

        }
        LOG.info("Started tracker service on {}", getAnnounceUris());
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
        LOG.info("Stopping tracker service on {}", getAnnounceUris());
        synchronized (lock) {

            for (Map.Entry<InetSocketAddress, Listener> e : listeners.entrySet()) {
                Listener listener = e.getValue();
                if (listener.connection != null) {
                    listener.connection.close();
                    listener.connection = null;
                }
                listener.connectionAddress = null;
            }

            this.service.stop();

            if (this.metrics != null) {
                this.metrics.shutdown();
                this.metrics = null;
            }
        }
        LOG.info("Stopped tracker service on {}", getAnnounceUris());
    }
}