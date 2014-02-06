/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.client;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Ignore;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author shevek
 */
public class ReplicationTest extends AbstractReplicationTest {

    private static final Logger LOG = LoggerFactory.getLogger(ReplicationTest.class);

    private void testReplication(int seed_delay, int nclients) throws Exception {
        if (seed_delay <= 0) {
            seed.start();
            Thread.sleep(-seed_delay);
        }

        CountDownLatch latch = new CountDownLatch(nclients);

        for (int i = 0; i < nclients; i++) {
            Client c = leech(latch, i);
            c.start();
        }

        if (seed_delay > 0) {
            Thread.sleep(seed_delay);
            seed.start();
        }

        await(latch);
    }

    // @Ignore
    @Test
    public void testReplicationSingleEarly() throws Exception {
        trackedTorrent.setAnnounceInterval(1, TimeUnit.MINUTES);
        testReplication(-500, 1);
    }

    // @Ignore
    @Test
    public void testReplicationSingleLate() throws Exception {
        testReplication(500, 1);
    }

    // @Ignore
    @Test
    public void testReplicationMultipleEarly() throws Exception {
        trackedTorrent.setAnnounceInterval(1, TimeUnit.MINUTES);
        testReplication(-500, 3);
    }

    // @Ignore
    @Test
    public void testReplicationMultipleLate() throws Exception {
        testReplication(500, 3);
    }

    @Test
    public void testHugeSwarm() throws Exception {
        testReplication(-500, 20);
    }
}
