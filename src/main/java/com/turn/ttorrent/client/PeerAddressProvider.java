/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.client;

import java.net.SocketAddress;
import java.util.Set;
import javax.annotation.Nonnull;

/**
 *
 * @author shevek
 */
public interface PeerAddressProvider {

    @Nonnull
    public Set<? extends SocketAddress> getLocalAddresses();
}
