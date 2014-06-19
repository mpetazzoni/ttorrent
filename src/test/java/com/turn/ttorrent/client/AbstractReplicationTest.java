/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.client;

import com.turn.ttorrent.common.Torrent;
import com.turn.ttorrent.common.TorrentCreator;
import com.turn.ttorrent.test.TorrentTestUtils;
import com.turn.ttorrent.tracker.TrackedTorrent;
import com.turn.ttorrent.tracker.Tracker;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import org.junit.After;
import org.junit.Before;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author shevek
 */
public class AbstractReplicationTest {

    private static final Logger LOG = LoggerFactory.getLogger(AbstractReplicationTest.class);
    protected Tracker tracker;
    protected Torrent torrent;
    protected TrackedTorrent trackedTorrent;
    protected Client seed;
    protected final List<Client> leechers = new ArrayList<Client>();

    @Before
    public void setUp() throws Exception {
        tracker = new Tracker(new InetSocketAddress("localhost", 0));
        tracker.start();

        File dir = TorrentTestUtils.newTorrentDir(getClass().getSimpleName() + ".seed");

        TorrentCreator creator = TorrentTestUtils.newTorrentCreator(dir, 126071);
        creator.setAnnounceList(tracker.getAnnounceUris());
        creator.setPieceLength(512);
        torrent = creator.create();

        trackedTorrent = tracker.announce(torrent);
        trackedTorrent.setAnnounceInterval(60, TimeUnit.SECONDS);

        seed = new Client("S-");
        TorrentHandler sharedTorrent = new TorrentHandler(seed, torrent, dir);
        sharedTorrent.setBlockLength(64);
        seed.addTorrent(sharedTorrent);
    }

    @After
    public void tearDown() throws Exception {
        for (Client leecher : leechers)
            leecher.stop();
        seed.stop();
        tracker.stop();
        Thread.sleep(1000); // Wait for socket release.
    }

    @Nonnull
    protected Client leech(@Nonnull CountDownLatch latch, int i) throws IOException, InterruptedException {
        File d = TorrentTestUtils.newTorrentDir(getClass().getSimpleName() + ".client" + i);
        Client c = new Client("L-" + i + "-");
        TorrentHandler sharedTorrent = new TorrentHandler(c, torrent, d);
        sharedTorrent.setBlockLength(64);
        c.addTorrent(sharedTorrent);
        c.addClientListener(new ReplicationCompletionListener(latch, TorrentHandler.State.SEEDING));
        leechers.add(c);
        return c;
    }

    protected void await(@Nonnull CountDownLatch latch) throws InterruptedException {
        for (;;) {
            if (latch.await(5, TimeUnit.SECONDS))
                break;
            seed.info(true);
            for (Client c : leechers)
                c.info(true);
        }
    }
}