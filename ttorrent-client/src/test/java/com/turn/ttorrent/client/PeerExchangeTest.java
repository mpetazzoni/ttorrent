/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.client;

import com.turn.ttorrent.client.io.PeerServer;
import com.turn.ttorrent.protocol.torrent.Torrent;
import com.turn.ttorrent.protocol.torrent.TorrentCreator;
import com.turn.ttorrent.protocol.test.TorrentTestUtils;
import com.turn.ttorrent.test.TorrentClientTestUtils;
import java.io.File;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author shevek
 */
public class PeerExchangeTest {

    private static final Logger LOG = LoggerFactory.getLogger(PeerExchangeTest.class);
    protected Torrent torrent;
    protected Client seed;
    protected Client[] peers;
    protected CountDownLatch latch;

    @Before
    public void setUp() throws Exception {
        SEED:
        {
            File dir = TorrentTestUtils.newTorrentDir(getClass().getSimpleName() + ".seed");
            TorrentCreator creator = TorrentTestUtils.newTorrentCreator(dir, 126071);
            creator.setPieceLength(512);
            torrent = creator.create();

            seed = new Client("S-");
            seed.addTorrent(torrent, dir);
        }

        peers = new Client[8];
        latch = new CountDownLatch(peers.length);

        for (int i = 0; i < peers.length; i++) {
            File dir = TorrentTestUtils.newTorrentDir(getClass().getSimpleName() + ".peer" + i);
            Client peer = new Client("C" + i + "-");
            // This lets PeerServer.getPeerAddresses() return an explicit localhost
            // which would otherwise be ignored as "local" while walking NetworkInterfaces.
            // peer.getEnvironment().setLocalPeerListenAddress(new InetSocketAddress("localhost", 6882 + i));
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

    @Nonnull
    private static InetSocketAddress toLocalhostAddress(@Nonnull PeerServer server) {
        InetSocketAddress in = server.getLocalAddress();
        return new InetSocketAddress("localhost", in.getPort());
    }

    private static void tellLeftAboutRight(Client left, Client right, byte[] torrentId) {
        SocketAddress peerAddress = toLocalhostAddress(right.getPeerServer());
        Map<SocketAddress, byte[]> peers = Collections.singletonMap(peerAddress, null);
        left.getTorrent(torrentId).getSwarmHandler().addPeers(Collections.singletonMap(peerAddress, (byte[]) null));
    }

    @Test
    public void testPeerExchange() throws Exception {
        for (Client peer : peers)
            peer.start();

        byte[] torrentId = torrent.getInfoHash();
        for (int i = 1; i < peers.length; i++) {
            // Tell each peer about the previous one.
            tellLeftAboutRight(peers[i], peers[i - 1], torrentId);
        }

        PEX:
        {
            SwarmHandler handler = peers[0].getTorrent(torrentId).getSwarmHandler();
            for (;;) {
                if (handler.getPeerCount() >= peers.length - 1) // It should know everyone but itself.
                    break;
                for (Client peer : peers)
                    peer.info(true);
                Thread.sleep(5000);
            }
            LOG.info("All peers exchanged!");
        }

        seed.start();
        tellLeftAboutRight(peers[0], seed, torrentId);
        await();

        for (Client peer : peers)
            TorrentClientTestUtils.assertTorrentData(seed, peer, torrentId);
    }
}
