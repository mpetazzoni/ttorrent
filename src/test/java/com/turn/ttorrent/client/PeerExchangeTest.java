/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.client;

import com.turn.ttorrent.client.io.PeerServer;
import com.turn.ttorrent.common.Torrent;
import com.turn.ttorrent.common.TorrentCreator;
import com.turn.ttorrent.test.TorrentTestUtils;
import java.io.File;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 *
 * @author shevek
 */
public class PeerExchangeTest {

    protected Torrent torrent;
    protected Client seed;
    protected Client[] peers;
    protected CountDownLatch latch;

    @Before
    public void setUp() throws Exception {
        int npeers = 4;

        SEED:
        {
            File dir = TorrentTestUtils.newTorrentDir(getClass().getSimpleName() + ".seed");
            TorrentCreator creator = TorrentTestUtils.newTorrentCreator(dir, 126071);
            creator.setPieceLength(512);
            torrent = creator.create();

            seed = new Client("S-");
            seed.addTorrent(torrent, dir);
        }

        peers = new Client[4];
        latch = new CountDownLatch(peers.length);

        for (int i = 0; i < peers.length; i++) {
            File dir = TorrentTestUtils.newTorrentDir(getClass().getSimpleName() + ".peer0");
            Client peer = new Client("C" + i + "-");
            TorrentHandler handler = peer.addTorrent(torrent, dir);
            peer.addClientListener(new ReplicationCompletionListener(latch, TorrentHandler.State.SEEDING));
            peers[i] = peer;
        }
    }

    @After
    public void tearDown() throws Exception {
        seed.stop();
        for (Client peer : peers)
            peer.stop();
        Thread.sleep(1000); // Wait for socket release.
    }

    protected void await() throws InterruptedException {
        for (;;) {
            if (latch.await(5, TimeUnit.SECONDS))
                break;
            seed.info(true);
            for (Client peer : peers)
                peer.info(true);
        }
    }

    private static InetSocketAddress toLocalhostAddress(@Nonnull PeerServer server) {
        InetSocketAddress in = server.getLocalAddress();
        return new InetSocketAddress("localhost", in.getPort());
    }

    @Test
    public void testPeerExchange() throws Exception {
        seed.start();
        for (Client peer : peers)
            peer.start();

        byte[] torrentId = torrent.getInfoHash();
        for (int i = 1; i < peers.length; i++) {
            // Tell each peer about the previous one.
            peers[i].getTorrent(torrentId).getSwarmHandler().addPeers(Arrays.asList(toLocalhostAddress(peers[i - 1].getPeerServer())));
        }

        SwarmHandler handler = peers[0].getTorrent(torrentId).getSwarmHandler();
        for (;;) {
            Thread.sleep(5000);
            if (handler.getPeerCount() == peers.length)
                break;
            for (Client peer : peers)
                peer.info(true);
        }

        handler.addPeers(Arrays.asList(toLocalhostAddress(seed.getPeerServer())));
        await();

        Thread.sleep(1000);
    }
}
