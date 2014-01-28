/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.test;

import com.google.common.io.Files;
import com.google.common.io.InputSupplier;
import com.turn.ttorrent.common.Torrent;
import com.turn.ttorrent.common.TorrentCreator;
import io.netty.util.ResourceLeakDetector;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.URISyntaxException;
import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;
import org.apache.commons.io.FileUtils;

/**
 *
 * @author shevek
 */
public class TorrentTestUtils {

    static {
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID);
    }
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
        File file = new File(dir, "torrent-data-file");
        Files.copy(new InputSupplier<InputStream>() {
            @Override
            public InputStream getInput() throws IOException {
                return new PatternInputStream(size);
            }
        }, file);

        return new TorrentCreator(file);
    }

    @Nonnull
    public static Torrent newTorrent(@Nonnull File dir, @Nonnegative long size)
            throws IOException, InterruptedException, URISyntaxException {
        return newTorrentCreator(dir, size).create();
    }
}
