/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.client;

import com.turn.ttorrent.common.Torrent;
import com.turn.ttorrent.common.TorrentCreator;
import com.turn.ttorrent.test.TorrentTestUtils;
import com.turn.ttorrent.tracker.Tracker;
import java.io.File;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author shevek
 */
public class ReplicationTest {

    private static final Logger LOG = LoggerFactory.getLogger(ReplicationTest.class);

    @Test
    public void testReplication() throws Exception {
        Tracker tracker = new Tracker(new InetSocketAddress("localhost", 5674));
        tracker.start();

        File d_seed = TorrentTestUtils.newTorrentDir("c_seed");
        TorrentCreator creator = TorrentTestUtils.newTorrentCreator(d_seed, 12345678, true);
        creator.setAnnounce(tracker.getAnnounceUrl().toURI());
        Torrent torrent = creator.create();

        tracker.announce(torrent);

        try {

            Client c_seed = new Client(torrent, d_seed);
            c_seed.start();

            int nclients = 1;
            final CountDownLatch latch = new CountDownLatch(nclients);

            class CompletionListener extends ClientListenerAdapter {

                private final Logger LOG = LoggerFactory.getLogger(CompletionListener.class);
                private final TorrentHandler.State state;

                public CompletionListener(TorrentHandler.State state) {
                    this.state = state;
                }

                @Override
                public void clientStateChanged(Client client, Client.State state) {
                    LOG.info("client=" + client + ", state=" + state);
                }

                @Override
                public void torrentStateChanged(Client client, TorrentHandler torrent, TorrentHandler.State state) {
                    LOG.info("client=" + client + ", torrent=" + torrent + ", state=" + state);
                    if (state == this.state)
                        latch.countDown();
                }
            }

            Client[] clients = new Client[nclients];
            for (int i = 0; i < nclients; i++) {
                File d = TorrentTestUtils.newTorrentDir("c" + i);
                Client c = new Client(torrent, d);
                c.addClientListener(new CompletionListener(TorrentHandler.State.SEEDING));
                c.start();
                clients[i] = c;
            }

            latch.await();

            for (Client c : clients) {
                c.stop();
            }

        } finally {
            tracker.stop();
        }
    }
}
