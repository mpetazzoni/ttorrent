/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.test;

import com.google.common.base.Functions;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.turn.ttorrent.client.peer.PeerExistenceListener;
import java.net.SocketAddress;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author shevek
 */
public class TestPeerExistenceListener implements PeerExistenceListener {

    private static final Logger LOG = LoggerFactory.getLogger(TestPeerExistenceListener.class);
    private final Set<SocketAddress> addresses = new HashSet<SocketAddress>();

    @Override
    public Map<? extends SocketAddress, ? extends byte[]> getPeers() {
        synchronized (addresses) {
            return Maps.asMap(addresses, Functions.<byte[]>constant(null));
        }
    }

    @Override
    public void addPeers(Map<? extends SocketAddress, ? extends byte[]> peers) {
        LOG.info("Added " + peers);
        synchronized (addresses) {
            Iterables.addAll(addresses, peers.keySet());
        }
    }
}
