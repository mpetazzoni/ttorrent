/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.test;

import com.turn.ttorrent.client.Client;
import com.turn.ttorrent.client.TorrentHandler;
import com.turn.ttorrent.client.storage.ByteStorage;
import io.netty.util.ResourceLeakDetector;
import java.io.IOException;
import java.nio.ByteBuffer;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.junit.Assert.*;

/**
 *
 * @author shevek
 */
public class TorrentClientTestUtils {

    private static final Logger LOG = LoggerFactory.getLogger(TorrentClientTestUtils.class);

    static {
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID);
    }

    public static void assertTorrentData(@Nonnull Client c0, @Nonnull Client c1, @Nonnull byte[] torrentId) throws IOException {
        TorrentHandler h0 = c0.getTorrent(torrentId);
        ByteStorage s0 = h0.getBucket();
        ByteBuffer b0 = ByteBuffer.allocate(64 * 1024);

        TorrentHandler h1 = c1.getTorrent(torrentId);
        ByteStorage s1 = h1.getBucket();
        ByteBuffer b1 = ByteBuffer.allocate(b0.capacity());

        for (long i = 0; i < h0.getSize(); i += b0.capacity()) {
            long len = Math.min(h0.getSize() - i, b0.capacity());
            LOG.info("Compare " + len + " bytes at " + i);

            b0.clear();
            b0.limit((int) len);
            s0.read(b0, i);

            b1.clear();
            b1.limit((int) len);
            s1.read(b1, i);

            assertArrayEquals(s0 + " != " + s1 + " @" + i, b0.array(), b1.array());
        }
    }
}
