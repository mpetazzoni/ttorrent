/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.tracker.client;

import com.turn.ttorrent.protocol.tracker.TrackerMessage;
import com.turn.ttorrent.test.TestTorrentMetadataProvider;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nonnull;
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
        public void handleDiscoveredPeers(URI tracker, Map<? extends SocketAddress, ? extends byte[]> peer) {
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

    @Nonnull
    private PeerAddressProvider newPeerAddresses() {
        return new PeerAddressProvider() {
            @Override
            public byte[] getLocalPeerId() {
                return new byte[]{4, 6, 8, 18};
            }

            @Override
            public Set<? extends SocketAddress> getLocalAddresses() {
                return Collections.singleton(new InetSocketAddress(17));
            }
        };
    }

    @Test
    public void testConnectionRefused() throws Exception {
        HTTPTrackerClient client = new HTTPTrackerClient(newPeerAddresses());
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
        HTTPTrackerClient client = new HTTPTrackerClient(newPeerAddresses());
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