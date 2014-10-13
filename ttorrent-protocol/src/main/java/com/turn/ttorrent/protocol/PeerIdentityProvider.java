/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.protocol;

import javax.annotation.Nonnull;

/**
 *
 * @author shevek
 */
public interface PeerIdentityProvider {

    @Nonnull
    public byte[] getLocalPeerId();

    @Nonnull
    public String getLocalPeerName();
}
