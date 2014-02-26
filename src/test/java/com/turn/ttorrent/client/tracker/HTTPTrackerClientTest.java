/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.client.tracker;

import com.turn.ttorrent.client.ClientEnvironment;
import com.turn.ttorrent.client.TorrentMetadataProvider;
import com.turn.ttorrent.common.protocol.TrackerMessage;
import com.turn.ttorrent.test.TestTorrentMetadataProvider;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 *
 * @author shevek
 */
public class HTTPTrackerClientTest {

    private static final Log LOG = LogFactory.getLog(HTTPTrackerClientTest.class);
    private final byte[] infoHash = new byte[]{1, 2, 3, 4, 5, 6, 7, 8};
    private final List<List<URI>> uris = new ArrayList<List<URI>>();
    private final TorrentMetadataProvider metadataProvider = new TestTorrentMetadataProvider(infoHash, uris);
    private final ClientEnvironment environment = new ClientEnvironment(getClass().getSimpleName());

    @Before
    public void setUp() throws Exception {
        environment.start();
    }

    @After
    public void tearDown() throws Exception {
        environment.stop();
    }

    private static class ResponseListener implements AnnounceResponseListener {

        private final CountDownLatch latch = new CountDownLatch(1);
        private final AtomicInteger failed = new AtomicInteger(0);

        @Override
        public void handleDiscoveredPeers(URI tracker, Collection<? extends SocketAddress> peerAddresses) {
            LOG.info("Peers: " + tracker);
            latch.countDown();
        }

        @Override
        public void handleAnnounceResponse(URI tracker, long interval, int complete, int incomplete) {
            LOG.info("Response: " + tracker);
            latch.countDown();
        }

        @Override
        public void handleAnnounceFailed(URI tracker, String reason) {
            LOG.info("Failed: " + tracker + ": " + reason);
            failed.getAndIncrement();
            latch.countDown();
        }
    }

    @Test
    public void testConnectionRefused() throws Exception {
        HTTPTrackerClient client = new HTTPTrackerClient(environment, new InetSocketAddress(17));
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
        HTTPTrackerClient client = new HTTPTrackerClient(environment, new InetSocketAddress(17));
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