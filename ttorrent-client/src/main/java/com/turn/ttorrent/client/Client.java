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

import com.turn.ttorrent.tracker.client.HTTPTrackerClient;
import com.turn.ttorrent.client.io.PeerClient;
import com.turn.ttorrent.client.io.PeerServer;
import com.turn.ttorrent.protocol.torrent.Torrent;
import com.turn.ttorrent.protocol.TorrentUtils;
import com.turn.ttorrent.tracker.client.TorrentMetadataProvider;
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
 * A BitTorrent client in its bare essence shares torrents.  If the torrent is
 * not complete locally, it will download pieces until it is complete, sharing
 * pieces that it has with other clients. Once a torrent is complete, the client
 * will seed the torrent until explicitly told to stop.
 * </p>
 *
 * <p>
 * This BitTorrent client implementation is made to be simple to embed and
 * simple to use. Until a Client is started (with {@link #start}), it will not
 * send or receive any data. At any point before or after starting the client,
 * torrents can be added to it with the
 * <code>addTorrent</code> methods. As soon as a torrent is registered and
 * the client is started, it will begin sharing the torrent. {@link Torrent} objects
 * can be created from .torrent files, and {@link TorrentHandler} objects add
 * the business logic necessary to interpret and use the metadata in a .torrent
 * file.
 * </p>
 *
 * <p>
 * TorrentHandlers can be retrieved or removed from a Client at any time, and
 * listeners can be registered with a Client that will inform a caller when the
 * Client or any torrent changes state.
 * </p>
 *
 * @author mpetazzoni
 */
public class Client implements TorrentRegistry {

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

    @Nonnull
    public ClientEnvironment getEnvironment() {
        return environment;
    }

    @Override
    public byte[] getLocalPeerId() {
        return getEnvironment().getLocalPeerId();
    }

    @Override
    public String getLocalPeerName() {
        return getEnvironment().getLocalPeerName();
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
                reportFuture = getEnvironment().getEventService().scheduleWithFixedDelay(new Runnable() {
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

            peerServer = new PeerServer(getEnvironment(), this);
            peerServer.start();
            peerClient = new PeerClient(getEnvironment());
            peerClient.start();

            httpTrackerClient = new HTTPTrackerClient(peerServer);
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

    @Override
    @CheckForNull
    public TorrentHandler getTorrent(@Nonnull byte[] infoHash) {
        String hexInfoHash = TorrentUtils.toHex(infoHash);
        return torrents.get(hexInfoHash);
    }

    @Nonnull
    public void addTorrent(@Nonnull TorrentHandler torrent) throws IOException, InterruptedException {
        // This lock guarantees that we are started or stopped.
        if (torrent.getClient() != this)
            throw new IllegalArgumentException("Wrong Client in TorrentHandler.");
        synchronized (lock) {
            torrents.put(TorrentUtils.toHex(torrent.getInfoHash()), torrent);
            if (getState() == State.STARTED)
                torrent.start();
        }
    }

    @Nonnull
    public TorrentHandler addTorrent(@Nonnull Torrent torrent, @Nonnull File destDir) throws IOException, InterruptedException {
        TorrentHandler torrentHandler = new TorrentHandler(this, torrent, destDir);
        addTorrent(torrentHandler);
        return torrentHandler;
    }

    public void removeTorrent(@Nonnull TorrentHandler torrent) throws IOException {
        synchronized (lock) {
            torrents.remove(TorrentUtils.toHex(torrent.getInfoHash()), torrent);
            if (getState() == State.STARTED)
                torrent.stop();
        }
    }

    @CheckForNull
    public TorrentHandler removeTorrent(@Nonnull Torrent torrent) throws IOException {
        return removeTorrent(torrent.getInfoHash());
    }

    @CheckForNull
    public TorrentHandler removeTorrent(@Nonnull byte[] infoHash) throws IOException {
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

    public void fireTorrentState(@Nonnull TorrentHandler torrent, @Nonnull TorrentMetadataProvider.State state) {
        for (ClientListener listener : listeners)
            listener.torrentStateChanged(this, torrent, state);
    }

    public void info(boolean verbose) {
        for (Map.Entry<String, TorrentHandler> e : torrents.entrySet()) {
            e.getValue().info(verbose);
        }
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "(" + getLocalPeerName() + ")";
    }
}