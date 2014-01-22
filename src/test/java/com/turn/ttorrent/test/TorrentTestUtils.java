/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.test;

import com.google.common.io.Files;
import com.google.common.io.InputSupplier;
import com.turn.ttorrent.common.Torrent;
import com.turn.ttorrent.common.TorrentCreator;
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

    private static File ROOT;

    @Nonnull
    public static synchronized File newTorrentRoot() throws IOException {
        if (ROOT != null)
            return ROOT;
        File root = File.createTempFile("ttorrent", ".seed");
        FileUtils.forceDeleteOnExit(root);
        FileUtils.forceDelete(root);
        FileUtils.forceMkdir(root);
        ROOT = root;
        return root;
    }

    @Nonnull
    public static File newTorrentDir(@Nonnull String name) throws IOException {
        File dir = new File(newTorrentRoot(), name);
        FileUtils.forceMkdir(dir);
        return dir;
    }

    @Nonnull
    public static Torrent newTorrent(@Nonnull File dir, @Nonnegative final long size, boolean random) throws IOException, URISyntaxException, InterruptedException {
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

        TorrentCreator creator = new TorrentCreator(file);
        return creator.create();
    }
}
