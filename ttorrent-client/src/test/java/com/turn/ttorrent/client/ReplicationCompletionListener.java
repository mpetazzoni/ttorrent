/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.client;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author shevek
 */
public class ReplicationCompletionListener extends ClientListenerAdapter {

    private final Logger LOG = LoggerFactory.getLogger(ReplicationCompletionListener.class);
    private final CountDownLatch latch;
    private final TorrentHandler.State state;
    private final Set<TorrentHandler> torrents = new HashSet<TorrentHandler>();
    private final Object lock = new Object();

    public ReplicationCompletionListener(@Nonnull CountDownLatch latch, @Nonnull TorrentHandler.State state) {
        this.latch = latch;
        this.state = state;
    }

    @Override
    public void clientStateChanged(Client client, Client.State state) {
        LOG.info("ClientState: client=" + client + ", state=" + state);
    }

    @Override
    public void torrentStateChanged(Client client, TorrentHandler torrent, TorrentHandler.State state) {
        LOG.info("TorrentState: client=" + client + ", torrent=" + torrent + ", state=" + state + ", latch=" + latch.toString());
        if (this.state.equals(state)) {
            LOG.info("Counting down for " + client.getLocalPeerName());
            synchronized (lock) {
                if (!torrents.add(torrent))
                    throw new IllegalArgumentException("Duplicate count for " + client.getLocalPeerName() + " / " + torrent);
            }
            latch.countDown();
        }
        /*
         switch (state) {
         case DONE:
         case SEEDING:
         try {
         client.stop();
         } catch (Exception e) {
         throw Throwables.propagate(e);
         }
         break;
         }
         */
    }
}
