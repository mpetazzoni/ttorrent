/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.client.storage;

import com.google.common.base.Throwables;
import com.google.common.io.Files;
import com.turn.ttorrent.test.TorrentTestUtils;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.junit.Assert.*;

/**
 *
 * @author shevek
 */
public class FileStorageTest {

    private static final Logger LOG = LoggerFactory.getLogger(FileStorageTest.class);

    @Test
    public void testStorage() throws Exception {
        File root = TorrentTestUtils.newTorrentDir("filestorage");

        final List<FileStorage> storage = new ArrayList<FileStorage>();
        for (int i = 0; i < 5; i++) {
            FileStorage s = new FileStorage(new File(root, "file" + i), 64);
            storage.add(s);
        }

        ExecutorService executor = Executors.newCachedThreadPool();
        final Random r = new Random();

        int ntasks = 100;
        final CountDownLatch latch = new CountDownLatch(ntasks);
        for (int i = 0; i < ntasks; i++) {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        FileStorage s = storage.get(r.nextInt(storage.size()));
                        ByteBuffer buf = ByteBuffer.wrap(new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
                        s.write(buf, r.nextInt(48));
                    } catch (IOException e) {
                        throw Throwables.propagate(e);
                    } finally {
                        latch.countDown();
                    }
                }
            });
        }
        latch.await();
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        for (FileStorage s : storage) {
            ByteBuffer buf = ByteBuffer.wrap(new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
            s.write(buf, 0);

            s.finish();

            byte[] data = Files.toByteArray(s.getFile());
            LOG.info("File contains " + Arrays.toString(data));
            for (int i = 0; i < 8; i++)
                assertEquals(i + 1, data[i]);
        }
    }
}