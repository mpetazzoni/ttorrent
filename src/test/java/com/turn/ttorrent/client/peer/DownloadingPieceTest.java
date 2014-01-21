/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.client.peer;

import com.google.common.io.Files;
import com.google.common.io.InputSupplier;
import com.google.common.math.IntMath;
import com.turn.ttorrent.client.Client;
import com.turn.ttorrent.client.PeerPieceProvider;
import com.turn.ttorrent.client.Piece;
import com.turn.ttorrent.client.SharedTorrent;
import com.turn.ttorrent.common.Torrent;
import com.turn.ttorrent.common.TorrentCreator;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.math.RoundingMode;
import java.net.InetAddress;
import java.net.URISyntaxException;
import java.util.Random;
import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;
import org.apache.commons.io.input.NullInputStream;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.junit.Assert.*;

/**
 *
 * @author shevek
 */
public class DownloadingPieceTest {

    private static final Logger LOG = LoggerFactory.getLogger(DownloadingPieceTest.class);

    private static class RandomInputStream extends NullInputStream {

        private final Random r = new Random(1234);

        public RandomInputStream(long size) {
            super(size);
        }

        @Override
        protected int processByte() {
            return r.nextInt() & 0xFF;
        }

        @Override
        protected void processBytes(byte[] bytes, int offset, int length) {
            if (offset == 0 && length == bytes.length) {
                r.nextBytes(bytes);
            } else {
                byte[] tmp = new byte[length];
                r.nextBytes(tmp);
                System.arraycopy(tmp, 0, bytes, offset, length);
            }
        }
    }

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

    @Test
    public void testPiece() throws Exception {
        Torrent torrent = newTorrent(465432, true);

        Client client = new Client(InetAddress.getLoopbackAddress());
        SharedTorrent torrentHandler = new SharedTorrent(client, torrent, new File("build/tmp"));
        torrentHandler.init();

        Piece piece = torrentHandler.getPiece(0);
        PeerPieceProvider provider = new SharingPeerTest.DummyPeerPieceProvider(torrent);
        DownloadingPiece pieceHandler = new DownloadingPiece(piece, provider);
        int blockCount = IntMath.divide(torrentHandler.getPieceLength(0), DownloadingPiece.DEFAULT_BLOCK_SIZE, RoundingMode.UP);
        for (int i = 0; i < blockCount; i++) {
            DownloadingPiece.AnswerableRequestMessage request = pieceHandler.nextRequest();
            LOG.info("Request is " + request);
            assertNotNull(request);
            request.validate(torrentHandler);
        }
        for (int i = 0; i < 3; i++) {
            DownloadingPiece.AnswerableRequestMessage request = pieceHandler.nextRequest();
            LOG.info("Request is " + request);
            assertNull(request);
        }
    }
}