/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.client.peer;

import java.net.SocketAddress;
import java.util.Collection;
import javax.annotation.Nonnull;

/**
 *
 * @author shevek
 */
public interface PeerExistenceListener {

    @Nonnull
    public Collection<? extends SocketAddress> getPeers();

    public void addPeers(@Nonnull Iterable<? extends SocketAddress> peerAddresses);
}
