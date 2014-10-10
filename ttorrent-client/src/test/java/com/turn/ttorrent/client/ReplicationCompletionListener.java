/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.client;

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

    public ReplicationCompletionListener(@Nonnull CountDownLatch latch, @Nonnull TorrentHandler.State state) {
        this.latch = latch;
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
