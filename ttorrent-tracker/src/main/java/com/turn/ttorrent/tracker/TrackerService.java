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

import com.turn.ttorrent.protocol.tracker.http.HTTPAnnounceRequestMessage;
import com.turn.ttorrent.protocol.tracker.http.HTTPAnnounceResponseMessage;
import com.codahale.metrics.Meter;
import com.turn.ttorrent.protocol.tracker.Peer;
import com.turn.ttorrent.protocol.tracker.TrackerMessage;
import com.turn.ttorrent.protocol.tracker.http.HTTPTrackerErrorMessage;
import com.turn.ttorrent.protocol.tracker.http.HTTPTrackerMessage;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abstract tracker service to serve the tracker's announce requests.
 * @see <a href="http://wiki.theory.org/BitTorrentSpecification">BitTorrent protocol specification</a>
 */
public class TrackerService {

    private static final Logger LOG = LoggerFactory.getLogger(TrackerService.class);

    protected static class Metrics {

        public final Meter requestRejected;
        public final Meter requestParseFailed;
        public final Meter requestReceived;
        public final Meter requestNoTorrent;
        public final Meter requestInvalidEvent;
        public final Meter requestUpdateFailed;
        public final Meter requestResponseFailed;
        public final Meter requestSucceeded;

        private Metrics(@Nonnull TrackerMetrics metrics) {
            this.requestRejected = metrics.addMeter("requestRejected", "requests", TimeUnit.SECONDS);
            this.requestParseFailed = metrics.addMeter("requestParseFailed", "requests", TimeUnit.SECONDS);
            this.requestReceived = metrics.addMeter("requestReceived", "requests", TimeUnit.SECONDS);
            this.requestNoTorrent = metrics.addMeter("requestNoTorrent", "requests", TimeUnit.SECONDS);
            this.requestInvalidEvent = metrics.addMeter("requestInvalidEvent", "requests", TimeUnit.SECONDS);
            this.requestUpdateFailed = metrics.addMeter("requestUpdateFailed", "requests", TimeUnit.SECONDS);
            this.requestResponseFailed = metrics.addMeter("requestResponseFailed", "requests", TimeUnit.SECONDS);
            this.requestSucceeded = metrics.addMeter("requestSucceeded", "requests", TimeUnit.SECONDS);
        }
    }
    private final TrackedTorrentRegistry torrents;
    protected Metrics metrics;
    private final Object lock = new Object();

    /**
     * Create a new TrackerService serving the given torrents.
     *
     * @param torrents The torrents this TrackerService should serve requests
     * for.
     */
    public TrackerService(@Nonnull TrackedTorrentRegistry torrents) {
        this.torrents = torrents;
    }

    public TrackerService() {
        this(new TrackedTorrentRegistry());
    }

    @Nonnull
    public TrackedTorrentRegistry getTorrents() {
        return torrents;
    }

    /**
     * Process the announce request.
     *
     * <p>
     * This method attempts to read and parse the incoming announce request into
     * an announce request message, then creates the appropriate announce
     * response message and sends it back to the client.
     * </p>
     *
     * @param request The incoming announce request.
     * @param response The response object.
     * @param body The validated response body output stream.
     */
    @Nonnull
    public HTTPTrackerMessage process(@Nonnull InetSocketAddress clientAddress, @Nonnull HTTPAnnounceRequestMessage request) {
        metrics.requestReceived.mark();

        if (LOG.isTraceEnabled())
            LOG.trace("Announce request is {}", request);

        // The requested torrent must be announced by the tracker.
        TrackedTorrent torrent = this.torrents.getTorrent(request.getHexInfoHash());
        if (torrent == null) {
            metrics.requestNoTorrent.mark();
            LOG.warn("No such torrent: {}", request.getHexInfoHash());
            return new HTTPTrackerErrorMessage(TrackerMessage.ErrorMessage.FailureReason.UNKNOWN_TORRENT);
        }

        int peerPort = -1;
        List<InetSocketAddress> peerAddresses = request.getPeerAddresses();
        Iterator<InetSocketAddress> peerAddressIterator = peerAddresses.iterator();
        while (peerAddressIterator.hasNext()) {
            InetSocketAddress peerAddress = peerAddressIterator.next();
            if (peerPort == -1)
                peerPort = peerAddress.getPort();
            if (!Peer.isValidIpAddress(peerAddress)) {
                LOG.debug("Peer specified invalid address {}", peerAddress);
                peerAddressIterator.remove();
            }
        }
        if (peerPort == -1) {
            LOG.debug("Peer specified no valid address.");
            return new HTTPTrackerErrorMessage(TrackerMessage.ErrorMessage.FailureReason.MISSING_PEER_ADDRESS);
        }
        if (peerAddresses.isEmpty()) {
            InetSocketAddress peerAddress = new InetSocketAddress(clientAddress.getAddress(), peerPort);
            LOG.debug("Peer specified no valid address; using {} instead.", peerAddress);
            peerAddresses.add(peerAddress);
        }

        TrackedPeer client = torrent.getPeer(request.getPeerId());

        TrackerMessage.AnnounceEvent event = request.getEvent();
        // When no event is specified, it's a periodic update while the client
        // is operating. If we don't have a peer for this announce, it means
        // the tracker restarted while the client was running. Consider this
        // announce request as a 'started' event.
        if ((event == null || TrackerMessage.AnnounceEvent.NONE.equals(event))
                && client == null) {
            event = TrackerMessage.AnnounceEvent.STARTED;
        }

        // If an event other than 'started' is specified and we also haven't
        // seen the peer on this torrent before, something went wrong. A
        // previous 'started' announce request should have been made by the
        // client that would have had us register that peer on the torrent this
        // request refers to.
        if (event != null && client == null && !TrackerMessage.AnnounceEvent.STARTED.equals(event)) {
            metrics.requestInvalidEvent.mark();
            return new HTTPTrackerErrorMessage(TrackerMessage.ErrorMessage.FailureReason.INVALID_EVENT);
        }

        // Update the torrent according to the announce event
        try {
            client = torrent.update(event,
                    request.getPeerId(),
                    peerAddresses,
                    request.getUploaded(),
                    request.getDownloaded(),
                    request.getLeft());
        } catch (Exception e) {
            metrics.requestUpdateFailed.mark();
            LOG.error("Failed to update torrent", e);
            return new HTTPTrackerErrorMessage(TrackerMessage.ErrorMessage.FailureReason.UPDATE_FAILED);
        }

        // Craft and output the answer
        try {
            HTTPAnnounceResponseMessage response = new HTTPAnnounceResponseMessage(
                    clientAddress.getAddress(),
                    (int) TimeUnit.MILLISECONDS.toSeconds(torrent.getAnnounceInterval()),
                    // TrackedTorrent.MIN_ANNOUNCE_INTERVAL_SECONDS,
                    torrent.seeders(),
                    torrent.leechers(),
                    torrent.getSomePeers(client, request.getNumWant()));
            metrics.requestSucceeded.mark();
            return response;
        } catch (Exception e) {
            metrics.requestResponseFailed.mark();
            LOG.error("Failed to send response", e);
            return new HTTPTrackerErrorMessage(TrackerMessage.ErrorMessage.FailureReason.SERVER_ERROR);
        }
    }

    /**
     * Start the tracker thread.
     */
    public void start(@Nonnull TrackerMetrics metrics) throws IOException {
        synchronized (lock) {
            if (this.metrics == null) {
                this.metrics = new Metrics(metrics);
            }
            this.torrents.start(metrics);
        }
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
        synchronized (lock) {
            this.torrents.stop();
            this.metrics = null;
        }
    }
}
