/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.protocol.torrent;

import com.google.common.io.ByteSource;
import com.google.common.io.Files;
import com.google.common.math.LongMath;
import com.turn.ttorrent.protocol.test.PatternInputStream;
import com.turn.ttorrent.protocol.test.TorrentTestUtils;
import java.io.File;
import java.math.RoundingMode;
import java.util.Random;
import org.junit.After;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.junit.Assert.*;

/**
 *
 * @author shevek
 */
public class TorrentCreatorTest {

    private static final Logger LOG = LoggerFactory.getLogger(TorrentCreatorTest.class);

    @After
    public void tearDown() throws Exception {
        for (int i = 0; i < 4; i++) {
            Thread.sleep(50);
            System.gc();
        }
    }

    @Test
    public void testCreatorSingle() throws Exception {
        File dir = TorrentTestUtils.newTorrentDir("single");
        TorrentCreator creator = TorrentTestUtils.newTorrentCreator(dir, 193723193);
        creator.create();
    }

    @Test
    public void testCreatorMultiple() throws Exception {
        final long length = 109372319;
        File dir = TorrentTestUtils.newTorrentDir("multiple");
        for (int i = 0; i < 4; i++) {
            File file = new File(dir, "file-" + i);
            Files.asByteSink(file).writeFrom(new PatternInputStream(length));
        }
        TorrentCreator creator = new TorrentCreator(dir);
        Torrent torrent = creator.create();

        // TODO: Assert that the hash came out right.
    }

    @Test
    public void testFuzz() throws Exception {
        Random r = new Random();
        File dir = TorrentTestUtils.newTorrentDir("fuzz");
        long total = 0L;
        for (int i = 0; i < 10; i++) {
            File file = new File(dir, "file-" + i);
            Files.asByteSink(file).writeFrom(new PatternInputStream(r.nextInt(17 + i * 187)));
            total += file.length();

            TorrentCreator creator = new TorrentCreator(dir);
            creator.setPieceLength(64);
            Torrent torrent = creator.create();
            assertEquals(LongMath.divide(total, creator.getPieceLength(), RoundingMode.CEILING),
                    torrent.getPieceCount());
        }
    }
}