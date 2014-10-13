/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.tracker.client;

import com.turn.ttorrent.protocol.PeerIdentityProvider;
import java.net.SocketAddress;
import java.util.Set;
import javax.annotation.Nonnull;

/**
 *
 * @author shevek
 */
public interface PeerAddressProvider extends PeerIdentityProvider {

    @Nonnull
    public Set<? extends SocketAddress> getLocalAddresses();
}
