/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.client;

import com.turn.ttorrent.client.io.PeerMessage;
import com.turn.ttorrent.client.peer.Instrumentation;
import com.turn.ttorrent.client.peer.PeerHandler;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.junit.Test;

/**
 *
 * @author shevek
 */
public class ReplicationTimeoutTest extends AbstractReplicationTest {

    private static final Log LOG = LogFactory.getLog(ReplicationTimeoutTest.class);

    @Test
    public void testReplicationTimeout() throws Exception {
        trackedTorrent.setAnnounceInterval(10, TimeUnit.MINUTES);

        final Random r = seed.getEnvironment().getRandom();
        seed.getEnvironment().setInstrumentation(new Instrumentation() {
            boolean dropped = false;

            @Override
            public synchronized PeerMessage.RequestMessage instrumentBlockRequest(PeerHandler peer, PeerPieceProvider provider, PeerMessage.RequestMessage request) {
                if (request == null)
                    return null;
                if (!dropped) {
                    // Drop at least one
                    LOG.info("Drop " + request);
                    dropped = true;
                    return null;
                }
                if (r.nextFloat() < 0.1) {
                    // And bin 1% of remaining block requests
                    LOG.info("Drop " + request);
                    return null;
                }
                return super.instrumentBlockRequest(peer, provider, request);
            }
        });
        seed.start();

        // Let the seed contact the tracker first.
        Thread.sleep(100);

        CountDownLatch latch = new CountDownLatch(1);
        Client c = leech(latch, 0);
        c.start();
        await(latch);
    }
}
