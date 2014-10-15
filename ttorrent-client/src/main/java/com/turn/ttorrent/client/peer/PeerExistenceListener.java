/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.client.peer;

import java.net.SocketAddress;
import java.util.Map;
import javax.annotation.Nonnull;

/**
 *
 * @author shevek
 */
public interface PeerExistenceListener {

    /** Returns all known peers, possibly including this peer's known addresses. */
    @Nonnull
    public Map<? extends SocketAddress, ? extends byte[]> getPeers();

    /** Adds SocketAddress -> PeerIds. The PeerId may be null if not known. */
    public void addPeers(@Nonnull Map<? extends SocketAddress, ? extends byte[]> peers);
}
