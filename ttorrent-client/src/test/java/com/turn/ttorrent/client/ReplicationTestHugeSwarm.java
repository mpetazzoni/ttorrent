/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.client;

import org.junit.Test;

/**
 *
 * @author shevek
 */
public class ReplicationTestHugeSwarm extends AbstractReplicationTest {

    @Test
    public void testHugeSwarm() throws Exception {
        testReplication(-500, 22);
    }
}
