/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.client.peer;

import com.turn.ttorrent.test.TestPeerPieceProvider;
import com.google.common.math.IntMath;
import com.turn.ttorrent.client.Client;
import com.turn.ttorrent.client.PeerPieceProvider;
import com.turn.ttorrent.client.Piece;
import com.turn.ttorrent.client.TorrentHandler;
import com.turn.ttorrent.common.Torrent;
import com.turn.ttorrent.test.TorrentTestUtils;
import java.io.File;
import java.math.RoundingMode;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.junit.Assert.*;

/**
 *
 * @author shevek
 */
public class PieceHandlerTest {

    private static final Logger LOG = LoggerFactory.getLogger(PieceHandlerTest.class);

    @Test
    public void testPiece() throws Exception {
        File dir = TorrentTestUtils.newTorrentDir("PieceHandlerTest");
        Torrent torrent = TorrentTestUtils.newTorrent(dir, 465432, true);

        Client client = new Client();
        TorrentHandler torrentHandler = new TorrentHandler(client, torrent, new File("build/tmp"));
        torrentHandler.init();

        Piece piece = torrentHandler.getPiece(0);
        PeerPieceProvider provider = new TestPeerPieceProvider(torrent);
        PieceHandler pieceHandler = new PieceHandler(piece, provider);
        int blockCount = IntMath.divide(torrentHandler.getPieceLength(0), PieceHandler.DEFAULT_BLOCK_SIZE, RoundingMode.UP);

        for (int i = 0; i < blockCount; i++) {
            PieceHandler.AnswerableRequestMessage request = pieceHandler.nextRequest();
            LOG.info("Request is " + request);
            assertNotNull(request);
            request.validate(torrentHandler);
        }

        for (int i = 0; i < 2; i++) {
            PieceHandler.AnswerableRequestMessage request = pieceHandler.nextRequest();
            LOG.info("Request is " + request);
            assertNull(request);
        }
    }
}