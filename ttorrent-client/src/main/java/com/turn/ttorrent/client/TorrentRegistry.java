/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.client;

import com.turn.ttorrent.protocol.PeerIdentityProvider;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

/**
 *
 * @author shevek
 */
public interface TorrentRegistry extends PeerIdentityProvider {

    @CheckForNull
    public TorrentHandler getTorrent(@Nonnull byte[] infoHash);
}
