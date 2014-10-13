/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.protocol.test;

import com.google.common.base.Charsets;
import com.turn.ttorrent.protocol.PeerIdentityProvider;
import com.turn.ttorrent.protocol.TorrentUtils;

/**
 *
 * @author shevek
 */
public class TestPeerIdentityProvider implements PeerIdentityProvider {

    @Override
    public byte[] getLocalPeerId() {
        return getClass().getSimpleName().getBytes(Charsets.ISO_8859_1);
    }

    @Override
    public String getLocalPeerName() {
        return TorrentUtils.toText(getLocalPeerId());
    }
}
