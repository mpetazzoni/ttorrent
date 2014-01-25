/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.test;

import com.turn.ttorrent.client.TorrentMetadataProvider;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import javax.annotation.Nonnull;

/**
 *
 * @author shevek
 */
public class TestTorrentMetadataProvider implements TorrentMetadataProvider {

    private final byte[] infoHash;
    private final URI uri;

    public TestTorrentMetadataProvider(@Nonnull byte[] infoHash, @Nonnull URI uri) {
        this.infoHash = infoHash;
        this.uri = uri;
    }

    @Override
    public byte[] getInfoHash() {
        return infoHash;
    }

    @Override
    public List<? extends List<? extends URI>> getAnnounceList() {
        return Arrays.asList(Arrays.asList(uri));
    }

    @Override
    public long getUploaded() {
        return 0L;
    }

    @Override
    public long getDownloaded() {
        return 0L;
    }

    @Override
    public long getLeft() {
        return 0L;
    }

    @Override
    public void addPeers(Iterable<? extends SocketAddress> peerAddresses) {
    }
}
