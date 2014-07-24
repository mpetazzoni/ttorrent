/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.test;

import com.google.common.collect.Iterables;
import com.turn.ttorrent.client.peer.PeerExistenceListener;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 *
 * @author shevek
 */
public class TestPeerExistenceListener implements PeerExistenceListener {

    private static final Log LOG = LogFactory.getLog(TestPeerExistenceListener.class);
    private final Set<SocketAddress> addresses = new HashSet<SocketAddress>();

    @Override
    public Collection<? extends SocketAddress> getPeers() {
        synchronized (addresses) {
            return new ArrayList<SocketAddress>(addresses);
        }
    }

    @Override
    public void addPeers(Iterable<? extends SocketAddress> peerAddresses) {
        LOG.info("Added " + peerAddresses);
        synchronized (addresses) {
            Iterables.addAll(addresses, peerAddresses);
        }
    }
}
