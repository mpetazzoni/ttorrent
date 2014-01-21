/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.client.peer;

import com.turn.ttorrent.client.Client;
import com.turn.ttorrent.client.Piece;
import com.turn.ttorrent.client.SharedTorrent;
import com.turn.ttorrent.common.Torrent;
import com.turn.ttorrent.common.TorrentCreator;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.URISyntaxException;
import org.apache.commons.io.FileUtils;
import org.junit.Test;

/**
 *
 * @author shevek
 */
public class DownloadingPieceTest {

    public static Torrent newTorrent() throws IOException, URISyntaxException, InterruptedException {
        File file = File.createTempFile("ttorrent", "data");
        file.deleteOnExit();
        RandomAccessFile raf = new RandomAccessFile(file, "rw");
        raf.setLength(1346);
        raf.close();

        TorrentCreator creator = new TorrentCreator(file);
        return creator.create();
    }

    @Test
    public void testPiece() throws Exception {
        Torrent torrent = newTorrent();

        Client client = new Client(InetAddress.getLoopbackAddress());
        SharedTorrent torrentHandler = new SharedTorrent(client, torrent, new File("build/tmp"));
        torrentHandler.init();

        Piece piece = torrentHandler.getPiece(0);
        DownloadingPiece pieceHandler = new DownloadingPiece(piece);
        pieceHandler.nextRequest();
    }
}