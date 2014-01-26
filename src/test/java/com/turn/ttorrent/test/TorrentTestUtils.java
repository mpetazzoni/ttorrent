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
        File rootDir = File.createTempFile("ttorrent", ".seed", buildDir);
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
        FileUtils.forceMkdir(torrentDir);
        return torrentDir;
    }

    @Nonnull
    public static TorrentCreator newTorrentCreator(@Nonnull File dir, @Nonnegative final long size, boolean random)
            throws IOException, InterruptedException {
        File file = new File(dir, "torrent-data-file");
        if (random) {
            Files.copy(new InputSupplier<InputStream>() {
                @Override
                public InputStream getInput() throws IOException {
                    return new RandomInputStream(size);
                }
            }, file);
        } else {
            RandomAccessFile raf = new RandomAccessFile(file, "rw");
            raf.setLength(size);
            raf.close();
        }

        return new TorrentCreator(file);
    }

    @Nonnull
    public static Torrent newTorrent(@Nonnull File dir, @Nonnegative long size, boolean random)
            throws IOException, InterruptedException, URISyntaxException {
        return newTorrentCreator(dir, size, random).create();
    }
}
