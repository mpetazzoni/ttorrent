/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.test;

import com.google.common.io.Files;
import com.turn.ttorrent.client.Client;
import com.turn.ttorrent.client.TorrentHandler;
import com.turn.ttorrent.client.storage.TorrentByteStorage;
import com.turn.ttorrent.common.Torrent;
import com.turn.ttorrent.common.TorrentCreator;
import io.netty.util.ResourceLeakDetector;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.junit.Assert.*;

/**
 *
 * @author shevek
 */
public class TorrentTestUtils {

    private static final Logger LOG = LoggerFactory.getLogger(TorrentTestUtils.class);

    static {
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID);
    }
    public static final String FILENAME = "torrent-data-file";
    private static File ROOT;

    @Nonnull
    public static synchronized File newTorrentRoot()
            throws IOException {
        if (ROOT != null)
            return ROOT;
        File buildDir = new File("build/tmp");
        FileUtils.forceMkdir(buildDir);
        File rootDir = File.createTempFile("ttorrent", ".tmp", buildDir);
        FileUtils.forceDeleteOnExit(rootDir);
        FileUtils.forceDelete(rootDir);
        FileUtils.forceMkdir(rootDir);
        ROOT = rootDir;
        return rootDir;
    }

    @Nonnull
    public static File newTorrentDir(@Nonnull String name)
            throws IOException {
        File torrentDir = new File(newTorrentRoot(), name);
        if (torrentDir.exists())
            FileUtils.forceDelete(torrentDir);
        FileUtils.forceMkdir(torrentDir);
        return torrentDir;
    }

    @Nonnull
    public static TorrentCreator newTorrentCreator(@Nonnull File dir, @Nonnegative final long size)
            throws IOException, InterruptedException {
        File file = new File(dir, FILENAME);
        Files.asByteSink(file).writeFrom(new PatternInputStream(size));
        return new TorrentCreator(file);
    }

    @Nonnull
    public static Torrent newTorrent(@Nonnull File dir, @Nonnegative long size)
            throws IOException, InterruptedException, URISyntaxException {
        return newTorrentCreator(dir, size).create();
    }

    public static void assertTorrentData(@Nonnull Client c0, @Nonnull Client c1, @Nonnull byte[] torrentId) throws IOException {
        TorrentHandler h0 = c0.getTorrent(torrentId);
        TorrentByteStorage s0 = h0.getBucket();
        ByteBuffer b0 = ByteBuffer.allocate(64 * 1024);

        TorrentHandler h1 = c1.getTorrent(torrentId);
        TorrentByteStorage s1 = h1.getBucket();
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
