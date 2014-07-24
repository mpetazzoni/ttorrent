/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.client;

import com.turn.ttorrent.common.Torrent;
import com.turn.ttorrent.common.TorrentCreator;
import com.turn.ttorrent.test.TorrentTestUtils;
import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 *
 * @author shevek
 */
public class PeerExchangeTest {

    protected Torrent torrent;
    protected Client seed;
    protected Client peer0;
    protected Client peer1;
    protected CountDownLatch latch = new CountDownLatch(2);

    @Before
    public void setUp() throws Exception {
        SEED:
        {
            File dir = TorrentTestUtils.newTorrentDir(getClass().getSimpleName() + ".seed");
            TorrentCreator creator = TorrentTestUtils.newTorrentCreator(dir, 126071);
            creator.setPieceLength(512);
            torrent = creator.create();

            seed = new Client("S-");
            seed.addTorrent(new TorrentHandler(seed, torrent, dir));
        }

        P0:
        {
            File dir = TorrentTestUtils.newTorrentDir(getClass().getSimpleName() + ".peer0");
            peer0 = new Client("C0-");
            peer0.addTorrent(new TorrentHandler(seed, torrent, dir));
            peer0.addClientListener(new ReplicationCompletionListener(latch, TorrentHandler.State.SEEDING));
        }

        P1:
        {
            File dir = TorrentTestUtils.newTorrentDir(getClass().getSimpleName() + ".peer1");
            peer1 = new Client("C1-");
            peer1.addTorrent(new TorrentHandler(seed, torrent, dir));
            peer1.addClientListener(new ReplicationCompletionListener(latch, TorrentHandler.State.SEEDING));
        }
    }

    @After
    public void tearDown() throws Exception {
        seed.stop();
        peer0.stop();
        peer1.stop();
        Thread.sleep(1000); // Wait for socket release.
    }

    protected void await() throws InterruptedException {
        for (;;) {
            if (latch.await(5, TimeUnit.SECONDS))
                break;
            seed.info(true);
            peer0.info(true);
            peer1.info(true);
        }
    }

    @Test
    public void testPeerExchange() throws Exception {
        seed.start();
        peer0.start();
        peer1.start();
        await();
    }
}
