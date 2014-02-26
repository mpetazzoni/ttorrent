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

import com.turn.ttorrent.bcodec.BytesBEncoder;
import com.turn.ttorrent.bcodec.StreamBEncoder;
import com.turn.ttorrent.common.Peer;
import com.turn.ttorrent.common.Torrent;
import com.turn.ttorrent.common.protocol.TrackerMessage;
import com.turn.ttorrent.common.protocol.TrackerMessage.AnnounceRequestMessage;
import com.turn.ttorrent.common.protocol.TrackerMessage.MessageValidationException;
import com.turn.ttorrent.common.protocol.http.*;

import com.yammer.metrics.core.Meter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

import java.util.concurrent.TimeUnit;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.simpleframework.http.Request;
import org.simpleframework.http.Response;
import org.simpleframework.http.Status;
import org.simpleframework.http.core.Container;

/**
 * Tracker service to serve the tracker's announce requests.
 *
 * <p>
 * It only serves announce requests on /announce, and only serves torrents the
 * {@link Tracker} it serves knows about.
 * </p>
 *
 * <p>
 * The list of torrents {@link #torrents} is a map of torrent hashes to their
 * corresponding Torrent objects, and is maintained by the {@link Tracker} this
 * service is part of. The TrackerService only has a reference to this map, and
 * does not modify it.
 * </p>
 *
 * @author mpetazzoni
 * @see <a href="http://wiki.theory.org/BitTorrentSpecification">BitTorrent protocol specification</a>
 */
public class TrackerService implements Container {

    private static final Logger LOG = LoggerFactory.getLogger(TrackerService.class);
    private final String version;
    private final ConcurrentMap<String, TrackedTorrent> torrents;
    private final Meter requestReceived;
    private final Meter requestParseFailed;
    private final Meter requestNoTorrent;
    private final Meter requestInvalidEvent;
    private final Meter requestUpdateFailed;
    private final Meter requestResponseFailed;

    /**
     * Create a new TrackerService serving the given torrents.
     *
     * @param torrents The torrents this TrackerService should serve requests
     * for.
     */
    TrackerService(String version, ConcurrentMap<String, TrackedTorrent> torrents, TrackerMetrics metrics) {
        this.version = version;
        this.torrents = torrents;
        this.requestReceived = metrics.addMeter("requestReceived", "requests", TimeUnit.SECONDS);
        this.requestParseFailed = metrics.addMeter("requestParseFailed", "requests", TimeUnit.SECONDS);
        this.requestNoTorrent = metrics.addMeter("requestNoTorrent", "requests", TimeUnit.SECONDS);
        this.requestInvalidEvent = metrics.addMeter("requestInvalidEvent", "requests", TimeUnit.SECONDS);
        this.requestUpdateFailed = metrics.addMeter("requestUpdateFailed", "requests", TimeUnit.SECONDS);
        this.requestResponseFailed = metrics.addMeter("requestResponseFailed", "requests", TimeUnit.SECONDS);
    }

    /**
     * Handle the incoming request on the tracker service.
     *
     * <p>
     * This makes sure the request is made to the tracker's announce URL, and
     * delegates handling of the request to the <em>process()</em> method after
     * preparing the response object.
     * </p>
     *
     * @param request The incoming HTTP request.
     * @param response The response object.
     */
    @Override
    public void handle(Request request, Response response) {
        this.requestReceived.mark();

        try {
            // Reject non-announce requests
            if (!Tracker.ANNOUNCE_URL.equals(request.getPath().toString())) {
                response.setCode(404);
                response.setText("Not Found");
                return;
            }
        } catch (Exception e) {
            LOG.warn("Error while parsing request", e);
            return;
        }

        OutputStream body = null;
        try {
            body = response.getOutputStream();
            this.process(request, response, body);
            body.flush();
        } catch (Exception e) {
            LOG.warn("Error while writing response: {}!", e.getMessage());
        } finally {
            IOUtils.closeQuietly(body);
        }
    }

    /**
     * Process the announce request.
     *
     * <p>
     * This method attemps to read and parse the incoming announce request into
     * an announce request message, then creates the appropriate announce
     * response message and sends it back to the client.
     * </p>
     *
     * @param request The incoming announce request.
     * @param response The response object.
     * @param body The validated response body output stream.
     */
    private void process(Request request, Response response,
            OutputStream body) throws IOException {
        // Prepare the response headers.
        response.set("Content-Type", "text/plain");
        response.set("Server", this.version);
        response.setDate("Date", System.currentTimeMillis());

        /**
         * Parse the query parameters into an announce request message.
         *
         * We need to rely on our own query parsing function because
         * SimpleHTTP's Query map will contain UTF-8 decoded parameters, which
         * doesn't work well for the byte-encoded strings we expect.
         */
        HTTPAnnounceRequestMessage announceRequest;
        try {
            announceRequest = this.parseQuery(request);
        } catch (MessageValidationException e) {
            requestParseFailed.mark();
            LOG.error("Failed to parse request", e);
            this.serveError(response, body, Status.BAD_REQUEST,
                    e.getMessage());
            return;
        }

        LOG.trace("Announce request is {}", announceRequest);

        // The requested torrent must be announced by the tracker.
        TrackedTorrent torrent = this.torrents.get(announceRequest.getHexInfoHash());
        if (torrent == null) {
            requestNoTorrent.mark();
            LOG.warn("No such torrent: {}", announceRequest.getHexInfoHash());
            this.serveError(response, body, Status.BAD_REQUEST,
                    TrackerMessage.ErrorMessage.FailureReason.UNKNOWN_TORRENT);
            return;
        }

        TrackerMessage.AnnounceEvent event = announceRequest.getEvent();

        InetSocketAddress peerAddress = announceRequest.getPeerAddress();
        if (!Peer.isValidIpAddress(peerAddress)) {
            InetSocketAddress discoveredPeerAddress = new InetSocketAddress(request.getClientAddress().getAddress(), peerAddress.getPort());
            LOG.debug("Peer specified address {}; using {} instead.", peerAddress, discoveredPeerAddress);
            peerAddress = discoveredPeerAddress;
        }
        if (!Peer.isValidIpAddress(peerAddress)) {
            LOG.debug("Peer address is invalid: {}", peerAddress);
            this.serveError(response, body, Status.BAD_REQUEST,
                    TrackerMessage.ErrorMessage.FailureReason.MISSING_PEER_ADDRESS);
        }
        TrackedPeer client = torrent.getPeer(peerAddress);

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
            requestInvalidEvent.mark();
            this.serveError(response, body, Status.BAD_REQUEST,
                    TrackerMessage.ErrorMessage.FailureReason.INVALID_EVENT);
            return;
        }

        // Update the torrent according to the announce event
        try {
            client = torrent.update(event,
                    peerAddress,
                    announceRequest.getPeerId(),
                    announceRequest.getUploaded(),
                    announceRequest.getDownloaded(),
                    announceRequest.getLeft());
        } catch (IllegalArgumentException e) {
            requestUpdateFailed.mark();
            LOG.error("Failed to update torrent", e);
            this.serveError(response, body, Status.BAD_REQUEST,
                    TrackerMessage.ErrorMessage.FailureReason.INVALID_EVENT);
            return;
        }

        // Craft and output the answer
        HTTPAnnounceResponseMessage announceResponse;
        try {
            announceResponse = new HTTPAnnounceResponseMessage(
                    request.getClientAddress().getAddress(),
                    (int) TimeUnit.MILLISECONDS.toSeconds(torrent.getAnnounceInterval()),
                    // TrackedTorrent.MIN_ANNOUNCE_INTERVAL_SECONDS,
                    // this.version,
                    torrent.seeders(),
                    torrent.leechers(),
                    torrent.getSomePeers(client, announceRequest.getNumWant()));
            BytesBEncoder encoder = new BytesBEncoder();
            encoder.bencode(announceResponse.toBEValue(announceRequest.getCompact(), announceRequest.getNoPeerIds()));
            body.write(encoder.toByteArray());  // This is the raw network stream.
        } catch (Exception e) {
            requestResponseFailed.mark();
            LOG.error("Failed to send response", e);
            this.serveError(response, body, Status.INTERNAL_SERVER_ERROR,
                    e.getMessage());
        }
    }

    /**
     * Parse the query parameters using our defined BYTE_ENCODING.
     *
     * <p>
     * Because we're expecting byte-encoded strings as query parameters, we
     * can't rely on SimpleHTTP's QueryParser which uses the wrong encoding for
     * the job and returns us unparsable byte data. We thus have to implement
     * our own little parsing method that uses BYTE_ENCODING to decode
     * parameters from the URI.
     * </p>
     *
     * <p>
     * <b>Note:</b> array parameters are not supported. If a key is present
     * multiple times in the URI, the latest value prevails. We don't really
     * need to implement this functionality as this never happens in the
     * Tracker HTTP protocol.
     * </p>
     *
     * @param request The request's full URI, including query parameters.
     * @return The {@link AnnounceRequestMessage} representing the client's
     * announce request.
     */
    private HTTPAnnounceRequestMessage parseQuery(Request request)
            throws IOException, MessageValidationException {
        Map<String, String> params = new HashMap<String, String>();

        try {
            String uri = request.getAddress().toString();
            for (String pair : uri.split("[?]")[1].split("&")) {
                String[] keyval = pair.split("[=]", 2);
                if (keyval.length == 1) {
                    parseParam(params, keyval[0], null);
                } else {
                    parseParam(params, keyval[0], keyval[1]);
                }
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            params.clear();
        }

        // Make sure we have the peer IP, fallbacking on the request's source
        // address if the peer didn't provide it.
        if (!params.containsKey("ip"))
            params.put("ip", request.getClientAddress().getAddress().getHostAddress());

        return HTTPAnnounceRequestMessage.fromParams(params);
    }

    private void parseParam(@Nonnull Map<String, String> params, @Nonnull String key, @CheckForNull String value) {
        try {
            if (value != null)
                value = URLDecoder.decode(value, Torrent.BYTE_ENCODING_NAME);
            else
                value = "";
            params.put(key, value);
        } catch (UnsupportedEncodingException uee) {
            // Ignore, act like parameter was not there
            if (LOG.isDebugEnabled())
                LOG.debug("Could not decode {}", value);
        }
    }

    /**
     * Write a {@link HTTPTrackerErrorMessage} to the response with the given
     * HTTP status code.
     *
     * @param response The HTTP response object.
     * @param body The response output stream to write to.
     * @param status The HTTP status code to return.
     * @param error The error reported by the tracker.
     */
    private void serveError(Response response, OutputStream body,
            Status status, HTTPTrackerErrorMessage error) throws IOException {
        response.setCode(status.getCode());
        response.setText(status.getDescription());
        LOG.warn("Could not process announce request ({}) !",
                error.getReason());
        StreamBEncoder encoder = new StreamBEncoder(body);
        encoder.bencode(error.toBEValue());
    }

    /**
     * Write an error message to the response with the given HTTP status code.
     *
     * @param response The HTTP response object.
     * @param body The response output stream to write to.
     * @param status The HTTP status code to return.
     * @param error The error message reported by the tracker.
     */
    private void serveError(Response response, OutputStream body,
            Status status, String error) throws IOException {
        this.serveError(response, body, status,
                new HTTPTrackerErrorMessage(error));
    }

    /**
     * Write a tracker failure reason code to the response with the given HTTP
     * status code.
     *
     * @param response The HTTP response object.
     * @param body The response output stream to write to.
     * @param status The HTTP status code to return.
     * @param reason The failure reason reported by the tracker.
     */
    private void serveError(Response response, OutputStream body,
            Status status, TrackerMessage.ErrorMessage.FailureReason reason) throws IOException {
        this.serveError(response, body, status, reason.getMessage());
    }
}
