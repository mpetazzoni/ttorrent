/*
 * Copyright 2014 shevek.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.turn.ttorrent.client.tracker;

import com.turn.ttorrent.bcodec.BEValue;
import com.turn.ttorrent.bcodec.InvalidBEncodingException;
import com.turn.ttorrent.bcodec.StreamBDecoder;
import com.turn.ttorrent.client.ClientEnvironment;
import com.turn.ttorrent.client.TorrentMetadataProvider;
import com.turn.ttorrent.common.protocol.TrackerMessage;
import com.turn.ttorrent.common.protocol.TrackerMessage.AnnounceRequestMessage;
import com.turn.ttorrent.common.protocol.http.HTTPAnnounceRequestMessage;
import com.turn.ttorrent.common.protocol.http.HTTPAnnounceResponseMessage;
import com.turn.ttorrent.common.protocol.http.HTTPTrackerErrorMessage;
import com.turn.ttorrent.common.protocol.http.HTTPTrackerMessage;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import javax.annotation.CheckForNull;
import javax.annotation.CheckForSigned;
import javax.annotation.Nonnull;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.impl.nio.client.HttpAsyncClients;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Announcer for HTTP trackers.
 *
 * @author shevek
 */
public class HTTPTrackerClient extends TrackerClient {

    protected static final Logger LOG = LoggerFactory.getLogger(HTTPTrackerClient.class);
    private CloseableHttpAsyncClient httpclient;

    public HTTPTrackerClient(@Nonnull ClientEnvironment environment, @Nonnull InetSocketAddress peerAddress) {
        super(environment, peerAddress);
    }

    @Override
    public void start() throws Exception {
        super.start();
        RequestConfig requestConfig = RequestConfig.custom()
                .setSocketTimeout(3000)
                .setConnectTimeout(3000)
                .build();
        httpclient = HttpAsyncClients.custom()
                .setDefaultRequestConfig(requestConfig)
                .build();
        httpclient.start();
    }

    @Override
    public void stop() throws Exception {
        httpclient.close();
        httpclient = null;
        super.stop();
    }

    private class HttpResponseCallback implements FutureCallback<HttpResponse> {

        private final AnnounceResponseListener listener;
        private final HttpUriRequest request;
        private final URI tracker;

        public HttpResponseCallback(AnnounceResponseListener listener, HttpUriRequest request, URI tracker) {
            this.listener = listener;
            this.request = request;
            this.tracker = tracker;
        }

        @Override
        public void completed(HttpResponse response) {
            if (LOG.isTraceEnabled())
                LOG.trace("Completed: {} -> {}", request.getRequestLine(), response.getStatusLine());
            try {
                HTTPTrackerMessage message = toMessage(response, -1);
                if (message != null)
                    handleTrackerAnnounceResponse(listener, tracker, message, false);
            } catch (Exception e) {
                if (LOG.isDebugEnabled())
                    LOG.debug("Failed to handle announce response", e);
                failed(e);
            }
        }

        @Override
        public void failed(Exception e) {
            // This error wasn't necessarily reported elsewhere.
            if (LOG.isDebugEnabled())
                LOG.debug("Failed: {} -> {}", request.getRequestLine(), e);
            // TODO: Pass failure back to TrackerHandler.
            // LOG.trace("Failed: " + request.getRequestLine(), e);
            listener.handleAnnounceFailed(tracker, "HTTP failed: " + e);
        }

        @Override
        public void cancelled() {
            LOG.trace("Cancelled: {}", request.getRequestLine());
        }
    }

    /**
     * Build, send and process a tracker announce request.
     *
     * <p>
     * This function first builds an announce request for the specified event
     * with all the required parameters. Then, the request is made to the
     * tracker and the response analyzed.
     * </p>
     *
     * <p>
     * All registered {@link AnnounceResponseListener} objects are then fired
     * with the decoded payload.
     * </p>
     *
     * @param event The announce event type (can be AnnounceEvent.NONE for
     * periodic updates).
     * @param inhibitEvents Prevent event listeners from being notified.
     */
    @Override
    public void announce(
            AnnounceResponseListener listener,
            TorrentMetadataProvider torrent,
            URI tracker,
            TrackerMessage.AnnounceEvent event,
            boolean inhibitEvents) throws AnnounceException {
        LOG.info("Announcing{} to tracker {} with {}U/{}D/{}L bytes...",
                new Object[]{
            TrackerClient.formatAnnounceEvent(event),
            tracker,
            torrent.getUploaded(),
            torrent.getDownloaded(),
            torrent.getLeft()
        });

        try {
            HTTPAnnounceRequestMessage message =
                    new HTTPAnnounceRequestMessage(
                    torrent.getInfoHash(),
                    getEnvironment().getLocalPeerId(), getPeerAddress(),
                    torrent.getUploaded(), torrent.getDownloaded(), torrent.getLeft(),
                    true, false, event, AnnounceRequestMessage.DEFAULT_NUM_WANT);
            URI target = message.toURI(tracker);
            HttpGet request = new HttpGet(target);
            HttpResponseCallback callback = new HttpResponseCallback(listener, request, tracker);
            httpclient.execute(request, callback);
        } catch (URISyntaxException mue) {
            throw new AnnounceException("Invalid announce URI ("
                    + mue.getMessage() + ")", mue);
        } catch (IOException ioe) {
            throw new AnnounceException("Error building announce request ("
                    + ioe.getMessage() + ")", ioe);
        }
    }

    // The tracker may return valid BEncoded data even if the status code
    // was not a 2xx code. On the other hand, it may return garbage.
    @CheckForNull
    public static HTTPTrackerMessage toMessage(@Nonnull HttpResponse response, @CheckForSigned long maxContentLength)
            throws IOException {
        HttpEntity entity = response.getEntity();
        if (entity == null) // Usually 204-no-content, etc.
            return null;
        try {
            if (maxContentLength >= 0) {
                long contentLength = entity.getContentLength();
                if (contentLength >= 0)
                    if (contentLength > maxContentLength)
                        throw new IllegalArgumentException("ContentLength was too big: " + contentLength + ": " + response);
            }

            InputStream in = entity.getContent();
            if (in == null)
                return null;
            try {
                StreamBDecoder decoder = new StreamBDecoder(in);
                BEValue value = decoder.bdecodeMap();
                Map<String, BEValue> params = value.getMap();
                // TODO: "warning message"
                if (params.containsKey("failure reason"))
                    return HTTPTrackerErrorMessage.fromBEValue(params);
                else
                    return HTTPAnnounceResponseMessage.fromBEValue(params);
            } finally {
                IOUtils.closeQuietly(in);
            }
        } catch (InvalidBEncodingException e) {
            throw new IOException("Failed to parse response " + response, e);
        } catch (TrackerMessage.MessageValidationException e) {
            throw new IOException("Failed to parse response " + response, e);
        } finally {
            EntityUtils.consumeQuietly(entity);
        }
    }
}
