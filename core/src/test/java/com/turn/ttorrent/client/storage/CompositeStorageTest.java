package com.turn.ttorrent.client.storage;

import org.testng.annotations.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/**
 * User: loyd
 * Date: 11/24/13
 * @author <a href="mailto:xianguang.zhou@outlook.com">Xianguang Zhou</a>
 */
public class CompositeStorageTest {
    @Test
    public void testSelect() throws Exception {
        final File file1 = File.createTempFile("testng", "fcst");
        file1.deleteOnExit();
        final File file2 = File.createTempFile("testng", "fcst");
        file2.deleteOnExit();

        final List<ChildStorageDecorator> childStorageDecorators = new ArrayList<ChildStorageDecorator>();
        childStorageDecorators.add(new ChildStorageDecorator(new FileStorage(file1, 2), 0));
        childStorageDecorators.add(new ChildStorageDecorator(new FileStorage(file2, 2), 2));
        final CompositeStorage storage = new CompositeStorage(childStorageDecorators, 4);
        // since all of these files already exist, we are considered finished
        assertTrue(storage.isFinished());

        // write to first file works
        write(new byte[]{1, 2}, 0, storage);
        check(new byte[]{1, 2}, file1);

        // write to second file works
        write(new byte[]{5, 6}, 2, storage);
        check(new byte[]{5, 6}, file2);

        // write to two files works
        write(new byte[]{8,9,10,11}, 0, storage);
        check(new byte[]{8,9}, file1);
        check(new byte[]{10,11}, file2);

        // make sure partial write into next file works
        write(new byte[]{100,101,102}, 0, storage);
        check(new byte[]{102,11}, file2);
    }

    private void write(byte[] bytes, int offset, CompositeStorage storage) throws IOException {
        storage.write(ByteBuffer.wrap(bytes), offset);
        storage.finish();
    }
    private void check(byte[] bytes, File f) throws IOException {
		final byte[] temp = new byte[bytes.length];
		FileInputStream fileInputStream = new FileInputStream(f);
		try {
			assertEquals(fileInputStream.read(temp), temp.length);
			assertEquals(temp, bytes);
		} finally {
			fileInputStream.close();
		}
    }
}
