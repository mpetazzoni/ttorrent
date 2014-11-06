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
package com.turn.ttorrent.tracker.simple;

import com.turn.ttorrent.protocol.tracker.http.HTTPTrackerErrorMessage;
import com.turn.ttorrent.protocol.tracker.http.HTTPAnnounceRequestMessage;
import com.turn.ttorrent.protocol.tracker.http.HTTPAnnounceResponseMessage;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Splitter;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimap;
import com.google.common.io.Closeables;
import com.turn.ttorrent.protocol.bcodec.BytesBEncoder;
import com.turn.ttorrent.protocol.tracker.TrackerMessage.AnnounceRequestMessage;
import com.turn.ttorrent.protocol.tracker.TrackerMessage.MessageValidationException;
import com.turn.ttorrent.protocol.tracker.http.HTTPTrackerMessage;
import com.turn.ttorrent.tracker.TrackedTorrentRegistry;
import com.turn.ttorrent.tracker.TrackerService;
import com.turn.ttorrent.tracker.TrackerUtils;
import java.io.IOException;
import java.io.OutputStream;
import javax.annotation.Nonnull;
import org.simpleframework.http.core.Container;
import org.simpleframework.http.Request;
import org.simpleframework.http.Response;
import org.simpleframework.http.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tracker service to serve the tracker's announce requests.
 *
 * <p>
 * It only serves announce requests on /announce, and only serves torrents the
 * {@link SimpleTracker} it serves knows about.
 * </p>
 *
 * <p>
 * The list of torrents {@link #torrents} is a map of torrent hashes to their
 * corresponding Torrent objects, and is maintained by the {@link SimpleTracker} this
 * service is part of. The TrackerService only has a reference to this map, and
 * does not modify it.
 * </p>
 *
 * @author mpetazzoni
 * @see <a href="http://wiki.theory.org/BitTorrentSpecification">BitTorrent protocol specification</a>
 */
public class SimpleTrackerService extends TrackerService implements Container {

    private static final Logger LOG = LoggerFactory.getLogger(SimpleTrackerService.class);
    private final String version;

    /**
     * Create a new TrackerService serving the given torrents.
     *
     * @param torrents The torrents this TrackerService should serve requests
     * for.
     */
    public SimpleTrackerService(@Nonnull String version, @Nonnull TrackedTorrentRegistry torrents) {
        super(torrents);
        this.version = version;
    }

    public SimpleTrackerService(@Nonnull String version) {
        this.version = version;
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
        // LOG.info("Request: " + request);
        try {
            // Reject non-announce requests
            if (!TrackerUtils.DEFAULT_ANNOUNCE_URL.equals(request.getPath().toString())) {
                metrics.requestRejected.mark();
                response.setStatus(Status.NOT_FOUND);
                response.close();
                return;
            }

            OutputStream body = null;
            try {
                body = response.getOutputStream();
                this.process(request, response, body);
                body.flush();
            } finally {
                Closeables.close(body, true);
            }

        } catch (Exception e) {
            LOG.warn("Error while handling request", e);
            return;
        }
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
    private void process(Request request, Response response, OutputStream body)
            throws IOException {
        // Prepare the response headers.
        response.setContentType("text/plain");
        response.setValue("Server", this.version);
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
            announceRequest = parseRequest(request);
        } catch (MessageValidationException e) {
            metrics.requestParseFailed.mark();
            LOG.error("Failed to parse request", e);
            this.serveError(response, Status.BAD_REQUEST, e.getMessage());
            return;
        }

        if (LOG.isTraceEnabled())
            LOG.trace("Announce request is {}", announceRequest);

        HTTPTrackerMessage announceResponse = super.process(request.getClientAddress(), announceRequest);
        if (announceResponse instanceof HTTPTrackerErrorMessage) {
            this.serveError(response, Status.BAD_REQUEST, (HTTPTrackerErrorMessage) announceResponse);
            return;
        }

        // Output the answer
        try {
            BytesBEncoder encoder = new BytesBEncoder();
            encoder.bencode(((HTTPAnnounceResponseMessage) announceResponse).toBEValue(announceRequest));
            body.write(encoder.toByteArray());  // This is the raw network stream.
        } catch (Exception e) {
            LOG.error("Failed to send response", e);
            this.serveError(response, Status.INTERNAL_SERVER_ERROR, e.getMessage());
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
     * @param request The Request object.
     * @return The {@link AnnounceRequestMessage} representing the client's
     * announce request.
     */
    @Nonnull
    @VisibleForTesting
    /* pp */ static HTTPAnnounceRequestMessage parseRequest(Request request)
            throws MessageValidationException {
        String uri = request.getAddress().toString();
        Iterable<String> it = Splitter.on('?').limit(2).split(uri);
        String query = Iterables.get(it, 1, null);
        if (query == null)
            throw new MessageValidationException("No query string.");
        Multimap<String, String> params = TrackerUtils.parseQuery(query);
        return HTTPAnnounceRequestMessage.fromParams(params);
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
    private void serveError(Response response, Status status, HTTPTrackerErrorMessage error) throws IOException {
        LOG.warn("Could not process announce request ({}) !", error.getReason());
        response.setStatus(status);
        BytesBEncoder encoder = new BytesBEncoder();
        encoder.bencode(error.toBEValue());
        response.getOutputStream().write(encoder.toByteArray());  // This is the raw network stream.
    }

    /**
     * Write an error message to the response with the given HTTP status code.
     *
     * @param response The HTTP response object.
     * @param body The response output stream to write to.
     * @param status The HTTP status code to return.
     * @param error The error message reported by the tracker.
     */
    private void serveError(Response response, Status status, String error) throws IOException {
        this.serveError(response, status, new HTTPTrackerErrorMessage(error));
    }
}