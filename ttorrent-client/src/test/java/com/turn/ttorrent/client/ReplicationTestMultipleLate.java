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
public class ReplicationTestMultipleLate extends AbstractReplicationTest {

    @Test
    public void testReplicationMultipleLate() throws Exception {
        testReplication(500, 3);
    }
}