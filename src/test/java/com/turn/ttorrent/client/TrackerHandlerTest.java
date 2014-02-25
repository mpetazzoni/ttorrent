/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.client;

import com.turn.ttorrent.common.Torrent;
import com.turn.ttorrent.common.TorrentCreator;
import com.turn.ttorrent.test.TestTorrentMetadataProvider;
import com.turn.ttorrent.test.TorrentTestUtils;
import com.turn.ttorrent.tracker.TrackedTorrent;
import com.turn.ttorrent.tracker.Tracker;
import java.io.File;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 *
 * @author shevek
 */
public class TrackerHandlerTest {

    @Test
    public void testTracking() throws Exception {
        Tracker tracker = new Tracker(new InetSocketAddress("localhost", 5674));
        tracker.start();

        try {
            File dir = TorrentTestUtils.newTorrentDir("c_seed");
            TorrentCreator creator = TorrentTestUtils.newTorrentCreator(dir, 12345678);
            creator.setAnnounceList(tracker.getAnnounceUris());
            Torrent torrent = creator.create();

            TrackedTorrent trackedTorrent = tracker.announce(torrent);
            trackedTorrent.setAnnounceInterval(1, TimeUnit.MILLISECONDS);

            Client client = new Client(getClass().getSimpleName());
            client.start();

            try {
                final CountDownLatch latch = new CountDownLatch(2);
                TorrentMetadataProvider torrentMetadataProvider = new TestTorrentMetadataProvider(torrent.getInfoHash(), tracker.getAnnounceUris()) {
                    @Override
                    public void addPeers(Iterable<? extends SocketAddress> peerAddresses) {
                        super.addPeers(peerAddresses);
                        latch.countDown();
                    }
                };
                TrackerHandler trackerHandler = new TrackerHandler(client, torrentMetadataProvider);
                trackerHandler.start();

                latch.await(30, TimeUnit.SECONDS);
                assertEquals(0, latch.getCount());

                trackerHandler.stop();
            } finally {
                client.stop();
            }
        } finally {
            tracker.stop();
        }
    }

    @Test
    public void testFailover() throws Exception {
        Client client = new Client(getClass().getSimpleName());
        client.start();

        try {
            byte[] infoHash = new byte[]{1, 2, 3, 4, 5, 6, 7, 8};
            URI uri0 = new URI("http://localhost:100/announce");  // Deliberately invalid.
            URI uri1 = new URI("http://localhost:101/announce");  // Deliberately invalid.
            List<URI> uris = Arrays.asList(uri0, uri1);
            TestTorrentMetadataProvider metadataProvider = new TestTorrentMetadataProvider(infoHash, uris);
            TrackerHandler trackerHandler = new TrackerHandler(client, metadataProvider) {
                @Override
                public void run() {
                }
            };
            trackerHandler.start();

            try {

                assertEquals(uri0, trackerHandler.getCurrentTracker().getUri());
                trackerHandler.handleAnnounceFailed(uri1);
                assertEquals(uri0, trackerHandler.getCurrentTracker().getUri());

                trackerHandler.handleAnnounceFailed(uri0);
                assertEquals(uri1, trackerHandler.getCurrentTracker().getUri());
                trackerHandler.handleAnnounceFailed(uri0);
                assertEquals(uri1, trackerHandler.getCurrentTracker().getUri());

                trackerHandler.handleAnnounceFailed(uri1);
                assertEquals(uri0, trackerHandler.getCurrentTracker().getUri());
                trackerHandler.handleAnnounceFailed(uri1);
                assertEquals(uri0, trackerHandler.getCurrentTracker().getUri());

            } finally {
                trackerHandler.stop();
            }

        } finally {
            client.stop();
        }

    }
}
