/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.tracker.client;

import com.turn.ttorrent.protocol.tracker.TrackerMessage;
import com.turn.ttorrent.tracker.client.test.TestPeerAddressProvider;
import com.turn.ttorrent.tracker.client.test.TestTorrentMetadataProvider;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.junit.Assert.*;

/**
 *
 * @author shevek
 */
public class HTTPTrackerClientTest {

    private static final Logger LOG = LoggerFactory.getLogger(HTTPTrackerClientTest.class);
    private final byte[] infoHash = new byte[]{1, 2, 3, 4, 5, 6, 7, 8};
    private final List<List<URI>> uris = new ArrayList<List<URI>>();
    private final TorrentMetadataProvider metadataProvider = new TestTorrentMetadataProvider(infoHash, uris);

    private static class ResponseListener implements AnnounceResponseListener {

        private final CountDownLatch latch = new CountDownLatch(1);
        private final AtomicInteger failed = new AtomicInteger(0);

        @Override
        public void handleAnnounceResponse(URI tracker, TrackerMessage.AnnounceEvent event, TrackerMessage.AnnounceResponseMessage response) {
            LOG.info("Response: " + tracker + ": " + event + " -> " + response);
            latch.countDown();
        }

        @Override
        public void handleAnnounceFailed(URI tracker, TrackerMessage.AnnounceEvent event, String reason) {
            LOG.info("Failed: " + tracker + ": " + event + " -> " + reason);
            failed.getAndIncrement();
            latch.countDown();
        }
    }

    @Test
    public void testConnectionRefused() throws Exception {
        HTTPTrackerClient client = new HTTPTrackerClient(new TestPeerAddressProvider());
        client.start();
        try {
            ResponseListener listener = new ResponseListener();
            URI uri = new URI("http://localhost:12/announce");  // Connection refused.
            client.announce(listener, metadataProvider, uri, TrackerMessage.AnnounceEvent.STARTED, true);
            listener.latch.await();
            assertEquals(1, listener.failed.get());
        } finally {
            client.stop();
        }
    }

    @Test
    public void testConnectionTimeout() throws Exception {
        HTTPTrackerClient client = new HTTPTrackerClient(new TestPeerAddressProvider());
        client.start();
        try {
            ResponseListener listener = new ResponseListener();
            URI uri = new URI("http://1.1.1.1:80/announce");  // Connection timeout, I hope.
            client.announce(listener, metadataProvider, uri, TrackerMessage.AnnounceEvent.STARTED, true);
            listener.latch.await();
            assertEquals(1, listener.failed.get());
        } finally {
            client.stop();
        }
    }
}