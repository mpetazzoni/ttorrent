/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.tracker.client.test;

import com.google.common.base.Charsets;
import com.turn.ttorrent.protocol.TorrentUtils;
import com.turn.ttorrent.tracker.client.PeerAddressProvider;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.Set;

/**
 *
 * @author shevek
 */
public class TestPeerAddressProvider /* extends TestPeerIdentityProvider */ implements PeerAddressProvider {

    @Override
    public byte[] getLocalPeerId() {
        return getClass().getSimpleName().getBytes(Charsets.ISO_8859_1);
    }

    @Override
    public String getLocalPeerName() {
        return TorrentUtils.toText(getLocalPeerId());
    }

    @Override
    public Set<? extends SocketAddress> getLocalAddresses() {
        return Collections.singleton(new InetSocketAddress(17));
    }
}
