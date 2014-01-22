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

/**
 *
 * @author shevek
 */
public class TorrentTestUtils {

    @Nonnull
    public static Torrent newTorrent(@Nonnegative final long size, boolean random) throws IOException, URISyntaxException, InterruptedException {
        File file = File.createTempFile("ttorrent", ".seed");
        file.deleteOnExit();
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
