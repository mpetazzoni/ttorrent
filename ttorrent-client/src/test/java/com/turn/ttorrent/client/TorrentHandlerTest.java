/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.client;

import com.turn.ttorrent.protocol.torrent.Torrent;
import com.turn.ttorrent.protocol.torrent.TorrentCreator;
import com.turn.ttorrent.test.TorrentTestUtils;
import java.io.File;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.junit.Assert.*;

/**
 *
 * @author shevek
 */
public class TorrentHandlerTest {

    private static final Logger LOG = LoggerFactory.getLogger(TorrentHandlerTest.class);

    private TorrentHandler test(Torrent torrent, File parent) throws Exception {
        Client client = new Client(getClass().getSimpleName());
        TorrentHandler torrentHandler = client.addTorrent(torrent, parent);
        client.getEnvironment().start();
        try {
            torrentHandler.init();
            LOG.info("Available is " + torrentHandler.getCompletedPieces());
            assertTrue("We have pieces.", torrentHandler.getPieceCount() > 0);
            return torrentHandler;
        } finally {
            client.getEnvironment().stop();
        }
    }

    @Test
    public void testMultiFileSeed() throws Exception {
        File d_seed = TorrentTestUtils.newTorrentDir("TorrentHandlerTest");
        TorrentCreator creator = TorrentTestUtils.newTorrentCreator(d_seed, 12345678);
        Torrent torrent = creator.create();
        TorrentHandler torrentHandler = test(torrent, d_seed);
        assertTrue("We are complete, i.e. a seed.", torrentHandler.isComplete());
    }

    @Test
    public void testSingleFileSeed() throws Exception {
        File d_seed = TorrentTestUtils.newTorrentDir("TorrentHandlerTest");
        TorrentCreator creator = TorrentTestUtils.newTorrentCreator(d_seed, 12345678);
        Torrent torrent = creator.create();
        File f_seed = new File(d_seed, TorrentTestUtils.FILENAME);
        TorrentHandler torrentHandler = test(torrent, f_seed);
        assertTrue("We are complete, i.e. a seed.", torrentHandler.isComplete());
    }

    @Test
    public void testMultiFileLeech() throws Exception {
        File d_seed = TorrentTestUtils.newTorrentDir("TorrentHandlerTest");
        TorrentCreator creator = TorrentTestUtils.newTorrentCreator(d_seed, 12345678);
        Torrent torrent = creator.create();
        File d_leech = TorrentTestUtils.newTorrentDir("TorrentHandlerTest");
        TorrentHandler torrentHandler = test(torrent, d_leech);
        assertEquals("We have no pieces.", 0, torrentHandler.getCompletedPieceCount());
        assertFalse("We are not complete, i.e. a seed.", torrentHandler.isComplete());
    }

    @Test
    public void testSingleFileLeech() throws Exception {
        File d_seed = TorrentTestUtils.newTorrentDir("TorrentHandlerTest");
        TorrentCreator creator = TorrentTestUtils.newTorrentCreator(d_seed, 12345678);
        Torrent torrent = creator.create();
        File d_leech = TorrentTestUtils.newTorrentDir("TorrentHandlerTest");
        File f_leech = new File(d_leech, TorrentTestUtils.FILENAME);
        TorrentHandler torrentHandler = test(torrent, f_leech);
        assertEquals("We have no pieces.", 0, torrentHandler.getCompletedPieceCount());
        assertFalse("We are not complete, i.e. a seed.", torrentHandler.isComplete());
    }
}