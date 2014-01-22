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
import com.turn.ttorrent.common.Peer;
import com.turn.ttorrent.common.Torrent;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
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

    private static final Logger logger = LoggerFactory.getLogger(Client.class);
    private static final String BITTORRENT_ID_PREFIX = "-TO0042-";
    private final ClientEnvironment environment;
    private final byte[] peerId;
    private PeerServer peerServer;
    private PeerClient peerClient;
    private HTTPTrackerClient httpTrackerClient;
    // private UDPTrackerClient udpTrackerClient;
    // TODO: Search ports for a free port.
    private final Map<String, SharedTorrent> torrents = new ConcurrentHashMap<String, SharedTorrent>();

    /**
     * Initialize the BitTorrent client.
     *
     * @param torrent The torrent to download and share.
     */
    public Client() {
        String id = Client.BITTORRENT_ID_PREFIX + UUID.randomUUID()
                .toString().split("-")[4];

        this.environment = new ClientEnvironment();
        this.peerId = Arrays.copyOf(id.getBytes(Torrent.BYTE_ENCODING), 20);

    }

    @Nonnull
    public ClientEnvironment getEnvironment() {
        return environment;
    }

    /**
     * Get this client's peer specification.
     */
    @Nonnull
    public byte[] getPeerId() {
        return peerId;
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
        return httpTrackerClient;
    }

    /*
     @Nonnull
     public UDPTrackerClient getUdpTrackerClient() {
     return udpTrackerClient;
     }
     */
    public void start() throws Exception {
        environment.start();

        peerServer = new PeerServer(this);
        peerServer.start();
        peerClient = new PeerClient(this);
        peerClient.start();

        Peer peer = new Peer(peerServer.getLocalAddress(), peerId);

        httpTrackerClient = new HTTPTrackerClient(environment, peer);
        httpTrackerClient.start();

        // udpTrackerClient = new UDPTrackerClient(environment, peer);
        // udpTrackerClient.start();

        logger.info("BitTorrent client [{}] started and listening at {}...",
                new Object[]{
            peer.getShortHexPeerId(),
            peer.getHostIdentifier()
        });
    }

    public void stop() throws Exception {
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
    }

    @CheckForNull
    public SharedTorrent getTorrent(@Nonnull byte[] infoHash) {
        String hexInfoHash = Torrent.byteArrayToHexString(infoHash);
        return torrents.get(hexInfoHash);
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