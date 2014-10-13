/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.protocol.test;

import com.google.common.io.Files;
import com.turn.ttorrent.protocol.torrent.Torrent;
import com.turn.ttorrent.protocol.torrent.TorrentCreator;
import io.netty.util.ResourceLeakDetector;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
}