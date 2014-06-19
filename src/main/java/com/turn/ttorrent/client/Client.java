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

import com.turn.ttorrent.client.tracker.HTTPTrackerClient;
import com.turn.ttorrent.client.io.PeerClient;
import com.turn.ttorrent.client.io.PeerServer;
import com.turn.ttorrent.common.Torrent;
import com.turn.ttorrent.common.TorrentUtils;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.concurrent.GuardedBy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A pure-java BitTorrent client.
 *
 * <p>
 * A BitTorrent client in its bare essence shares torrents. If the
 * torrent is not complete locally, it will continue to download it. If or
 * after the torrent is complete, the client may eventually continue to seed it
 * for other clients.
 * </p>
 *
 * <p>
 * This BitTorrent client implementation is made to be simple to embed and
 * simple to use. First, initialize a ShareTorrent object from a torrent
 * meta-info source (either a file or a byte array, see
 * com.turn.ttorrent.SharedTorrent for how to create a SharedTorrent object).
 * Then, instantiate your Client object with this SharedTorrent and call one of
 * {@link #download} to simply download the torrent, or {@link #share} to
 * download and continue seeding for the given amount of time after the
 * download completes.
 * </p>
 *
 * @author mpetazzoni
 */
public class Client {

    private static final Logger LOG = LoggerFactory.getLogger(Client.class);

    public enum State {

        STOPPED, STARTING, STARTED, STOPPING;
    }
    private final ClientEnvironment environment;
    @GuardedBy("lock")
    private State state = State.STOPPED;
    private PeerServer peerServer;
    private PeerClient peerClient;
    private HTTPTrackerClient httpTrackerClient;
    // private UDPTrackerClient udpTrackerClient;
    private long reportInterval = -1;
    private Future<?> reportFuture = null;
    private final ConcurrentMap<String, TorrentHandler> torrents = new ConcurrentHashMap<String, TorrentHandler>();
    private final List<ClientListener> listeners = new CopyOnWriteArrayList<ClientListener>();
    private final Object lock = new Object();

    /**
     * Initialize the BitTorrent client.
     */
    public Client() {
        this(null);
    }

    public Client(@CheckForNull String peerName) {
        this.environment = new ClientEnvironment(peerName);
    }

    /**
     * A convenience constructor to start with a single torrent.
     * 
     * @param torrent The torrent to download and share.
     */
    @Deprecated // I never much liked this constructor.
    public Client(@Nonnull Torrent torrent, @Nonnull File outputDir) throws IOException, InterruptedException {
        this(torrent.getName());
        addTorrent(new TorrentHandler(this, torrent, outputDir));
    }

    @Nonnull
    public ClientEnvironment getEnvironment() {
        return environment;
    }

    @Nonnull
    public byte[] getLocalPeerId() {
        return getEnvironment().getLocalPeerId();
    }

    @Nonnull
    public State getState() {
        synchronized (lock) {
            return state;
        }
    }

    private void setState(@Nonnull State state) {
        synchronized (lock) {
            this.state = state;
        }
        fireClientState(state);
    }

    @Nonnull
    public PeerServer getPeerServer() {
        return peerServer;
    }

    @Nonnull
    public PeerClient getPeerClient() {
        return peerClient;
    }

    @Nonnull
    public HTTPTrackerClient getHttpTrackerClient() {
        // Not locked, as should only be read after a happen-before event.
        // Causes a deadlock against stop() as called from TrackerHandler.
        HTTPTrackerClient h = httpTrackerClient;
        if (h == null)
            throw new IllegalStateException("No HTTPTrackerClient - bad state: " + this);
        return h;
    }

    public long getReportInterval() {
        return reportInterval;
    }

    public void setReportInterval(long reportInterval, TimeUnit unit) {
        this.reportInterval = unit.toMillis(reportInterval);
        rereport();
    }

    public void rereport() {
        synchronized (lock) {
            if (reportFuture != null)
                reportFuture.cancel(false);
            if (getState() == State.STARTED && reportInterval > 0)
                reportFuture = getEnvironment().getSchedulerService().scheduleWithFixedDelay(new Runnable() {
                    @Override
                    public void run() {
                        info(true);
                    }
                }, 0, reportInterval, TimeUnit.MILLISECONDS);
            else
                reportFuture = null;
        }
    }

    /*
     @Nonnull
     public UDPTrackerClient getUdpTrackerClient() {
     return udpTrackerClient;
     }
     */
    public void start() throws Exception {
        LOG.info("BitTorrent client [{}] starting...", this);
        synchronized (lock) {
            setState(State.STARTING);

            environment.start();

            peerServer = new PeerServer(this);
            peerServer.start();
            peerClient = new PeerClient(this);
            peerClient.start();

            httpTrackerClient = new HTTPTrackerClient(environment, peerServer.getLocalAddresses());
            httpTrackerClient.start();

            // udpTrackerClient = new UDPTrackerClient(environment, peer);
            // udpTrackerClient.start();

            for (TorrentHandler torrent : torrents.values())
                torrent.start();

            setState(State.STARTED);

            rereport();
        }
        LOG.info("BitTorrent client [{}] started.", this);
    }

    public void stop() throws Exception {
        LOG.info("BitTorrent client [{}] stopping...", this);
        synchronized (lock) {
            setState(State.STOPPING);

            rereport();

            for (TorrentHandler torrent : torrents.values())
                torrent.stop();

            // if (udpTrackerClient != null)
            // udpTrackerClient.stop();
            // udpTrackerClient = null;
            if (httpTrackerClient != null)
                httpTrackerClient.stop();
            httpTrackerClient = null;
            if (peerClient != null)
                peerClient.stop();
            peerClient = null;
            if (peerServer != null)
                peerServer.stop();
            environment.stop();

            setState(State.STOPPED);
        }
        LOG.info("BitTorrent client [{}] stopped.", this);
    }

    @CheckForNull
    public TorrentHandler getTorrent(@Nonnull byte[] infoHash) {
        String hexInfoHash = TorrentUtils.toHex(infoHash);
        return torrents.get(hexInfoHash);
    }

    public void addTorrent(@Nonnull TorrentHandler torrent) throws IOException, InterruptedException {
        // This lock guarantees that we are started or stopped.
        synchronized (lock) {
            torrents.put(TorrentUtils.toHex(torrent.getInfoHash()), torrent);
            if (getState() == State.STARTED)
                torrent.start();
        }
    }

    public void removeTorrent(@Nonnull TorrentHandler torrent) {
        synchronized (lock) {
            torrents.remove(TorrentUtils.toHex(torrent.getInfoHash()), torrent);
            if (getState() == State.STARTED)
                torrent.stop();
        }
    }

    @CheckForNull
    public TorrentHandler removeTorrent(@Nonnull Torrent torrent) {
        return removeTorrent(torrent.getInfoHash());
    }

    @CheckForNull
    public TorrentHandler removeTorrent(@Nonnull byte[] infoHash) {
        TorrentHandler torrent = torrents.get(TorrentUtils.toHex(infoHash));
        if (torrent != null)
            removeTorrent(torrent);
        return torrent;
    }

    public void addClientListener(@Nonnull ClientListener listener) {
        listeners.add(listener);
    }

    private void fireClientState(@Nonnull State state) {
        for (ClientListener listener : listeners)
            listener.clientStateChanged(this, state);
    }

    public void fireTorrentState(@Nonnull TorrentHandler torrent, @Nonnull TorrentHandler.State state) {
        for (ClientListener listener : listeners)
            listener.torrentStateChanged(this, torrent, state);
    }

    public void info(boolean verbose) {
        for (Map.Entry<String, TorrentHandler> e : torrents.entrySet()) {
            e.getValue().info(verbose);
        }
    }
    /**
     * Main client loop.
     *
     * <p>
     * The main client download loop is very simple: it starts the announce
     * request thread, the incoming connection handler service, and loops
     * unchoking peers every UNCHOKING_FREQUENCY seconds until told to stop.
     * Every OPTIMISTIC_UNCHOKE_ITERATIONS, an optimistic unchoke will be
     * attempted to try out other peers.
     * </p>
     *
     * <p>
     * Once done, it stops the announce and connection services, and returns.
     * </p>
     @Override
     public void run() {
     // First, analyze the torrent's local data.
     try {
     try {
     this.setState(ClientState.VALIDATING);
     this.torrent.init();
     } catch (IOException ioe) {
     logger.warn("Error while initializing torrent data: {}!",
     ioe.getMessage(), ioe);
     this.setState(ClientState.ERROR);
     return;
     } catch (InterruptedException ie) {
     logger.warn("Client was interrupted during initialization. "
     + "Aborting right away.");
     this.setState(ClientState.ERROR);
     return;
     }

     // Initial completion test
     if (this.torrent.isComplete()) {
     this.seed();
     } else {
     this.setState(ClientState.SHARING);
     }

     // Detect early stop
     if (this.stop) {
     logger.info("Download is complete and no seeding was requested.");
     this.finish();
     return;
     }

     this.announce.start();
     this.service.start();

     int optimisticIterations = 0;
     int rateComputationIterations = 0;

     while (!this.stop) {
     optimisticIterations =
     (optimisticIterations == 0
     ? Client.OPTIMISTIC_UNCHOKE_ITERATIONS
     : optimisticIterations - 1);

     rateComputationIterations =
     (rateComputationIterations == 0
     ? Client.RATE_COMPUTATION_ITERATIONS
     : rateComputationIterations - 1);

     try {
     this.unchokePeers(optimisticIterations == 0);
     this.info();
     if (rateComputationIterations == 0) {
     this.resetPeerRates();
     }
     } catch (Exception e) {
     logger.error("An exception occurred during the BitTorrent "
     + "client main loop execution!", e);
     }

     try {
     Thread.sleep(Client.UNCHOKING_FREQUENCY * 1000);
     } catch (InterruptedException ie) {
     logger.trace("BitTorrent main loop interrupted.");
     }
     }

     } finally {
     logger.debug("Stopping BitTorrent client connection service "
     + "and announce threads...");

     this.service.stop();
     try {
     this.service.close();
     } catch (IOException ioe) {
     logger.warn("Error while releasing bound channel: {}!",
     ioe.getMessage(), ioe);
     }

     this.announce.stop();

     // Close all peer connections
     logger.debug("Closing all remaining peer connections...");
     for (SharingPeer peer : this.connected.values()) {
     peer.unbind(true);
     }

     this.finish();
     }
     }
     */
}