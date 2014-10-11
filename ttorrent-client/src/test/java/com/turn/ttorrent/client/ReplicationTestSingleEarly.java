/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.client;

import java.util.concurrent.TimeUnit;
import org.junit.Test;

/**
 *
 * @author shevek
 */
public class ReplicationTestSingleEarly extends AbstractReplicationTest {

    @Test
    public void testReplicationSingleEarly() throws Exception {
        trackedTorrent.setAnnounceInterval(1, TimeUnit.MINUTES);
        testReplication(-500, 1);
    }
}