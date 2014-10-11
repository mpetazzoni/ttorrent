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
public class ReplicationTestSingleLate extends AbstractReplicationTest {

    @Test
    public void testReplicationSingleLate() throws Exception {
        testReplication(500, 1);
    }
}