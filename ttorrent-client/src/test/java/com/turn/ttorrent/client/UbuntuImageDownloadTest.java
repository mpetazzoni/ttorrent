/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.client;

import com.google.common.io.Files;
import com.google.common.io.Resources;
import com.turn.ttorrent.protocol.torrent.Torrent;
import com.turn.ttorrent.tracker.client.TorrentMetadataProvider;
import java.io.File;
import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Ignore;
import org.junit.Test;

/**
 *
 * @author joel
 */
public class UbuntuImageDownloadTest {

    @Ignore
    @Test
    public void testDownloadImage() throws Exception {
        File torrentFile = File.createTempFile("ttorrent-ubuntu", ".torrent");
        File image = File.createTempFile("ttorrent-ubuntu", ".img");
        //TODO(jfriedly): Remove when this works.
        torrentFile.deleteOnExit();
        image.deleteOnExit();
        URL url = new URL("http://releases.ubuntu.com/14.10/ubuntu-14.10-desktop-amd64.iso.torrent");
        Resources.asByteSource(url).copyTo(Files.asByteSink(torrentFile));

        Client c = new Client(null);
        Torrent torrent = new Torrent(torrentFile);
        c.addTorrent(torrent, image);

        CountDownLatch latch = new CountDownLatch(1);
        c.addClientListener(new ReplicationCompletionListener(latch, TorrentMetadataProvider.State.SEEDING));

        try {
            c.start();
            for (;;) {
                if (latch.await(10, TimeUnit.SECONDS))
                    break;
                c.info(true);
            }
        } finally {
            c.stop();
        }

        torrentFile.delete();
        image.delete();
    }
}
