/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.client.peer;

import com.turn.ttorrent.test.TestPeerPieceProvider;
import com.google.common.math.IntMath;
import com.turn.ttorrent.client.PeerPieceProvider;
import com.turn.ttorrent.protocol.torrent.Torrent;
import com.turn.ttorrent.test.TorrentTestUtils;
import java.io.File;
import java.math.RoundingMode;
import java.util.Iterator;
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
        Torrent torrent = TorrentTestUtils.newTorrent(dir, 465432);

        PeerPieceProvider provider = new TestPeerPieceProvider(torrent);
        PieceHandler pieceHandler = new PieceHandler(provider, 0);
        int blockCount = IntMath.divide(torrent.getPieceLength(0), PieceHandler.DEFAULT_BLOCK_SIZE, RoundingMode.UP);
        Iterator<PieceHandler.AnswerableRequestMessage> it = pieceHandler.iterator();

        for (int i = 0; i < blockCount; i++) {
            assertTrue(it.hasNext());
            PieceHandler.AnswerableRequestMessage request = it.next();
            LOG.info("Request is " + request);
            assertNotNull(request);
            request.validate(provider);
        }

        for (int i = 0; i < 2; i++) {
            assertFalse(it.hasNext());
        }
    }
}