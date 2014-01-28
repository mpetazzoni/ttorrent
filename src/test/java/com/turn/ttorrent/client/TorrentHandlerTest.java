/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.client;

import com.turn.ttorrent.common.Torrent;
import com.turn.ttorrent.common.TorrentCreator;
import com.turn.ttorrent.test.TorrentTestUtils;
import java.io.File;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 *
 * @author shevek
 */
public class TorrentHandlerTest {

    private static final Log LOG = LogFactory.getLog(TorrentHandlerTest.class);

    @Test
    public void testTorrentHandler() throws Exception {
        File d_seed = TorrentTestUtils.newTorrentDir("TorrentHandlerTest");
        TorrentCreator creator = TorrentTestUtils.newTorrentCreator(d_seed, 12345678);
        Torrent torrent = creator.create();

        Client client = new Client(getClass().getSimpleName());
        TorrentHandler torrentHandler = new TorrentHandler(client, torrent, d_seed);
        client.addTorrent(torrentHandler);
        client.getEnvironment().start();
        try {
            torrentHandler.init();

            assertTrue("We have pieces.", torrentHandler.getPieceCount() > 0);
            assertTrue("We are complete, i.e. a seed.", torrentHandler.isComplete());
            LOG.info("Available is " + torrentHandler.getCompletedPieces());
        } finally {
            client.getEnvironment().stop();
        }
    }
}