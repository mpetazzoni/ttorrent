package com.turn.ttorrent.client.storage;

import com.turn.ttorrent.TempFiles;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
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
 */
public class FileCollectionStorageTest {

  private TempFiles tempFiles;

  @BeforeMethod
  public void setUp() {
    tempFiles = new TempFiles();
  }

  @AfterMethod
  public void tearDown() {
    tempFiles.cleanup();
  }

  @Test
  public void testSelect() throws Exception {
    final File file1 = tempFiles.createTempFile();
    final File file2 = tempFiles.createTempFile();

    final List<FileStorage> files = new ArrayList<FileStorage>();
    files.add(new FileStorage(file1, 0, 2));
    files.add(new FileStorage(file2, 2, 2));
    final FileCollectionStorage storage = new FileCollectionStorage(files, 4);

    storage.open(false);
    try {
      // since all of these files already exist, we are considered finished
      assertTrue(storage.isFinished());

      // write to first file works
      write(new byte[]{1, 2}, 0, storage);
      check(new byte[]{1, 2}, file1);

      // write to second file works
      write(new byte[]{5, 6}, 2, storage);
      check(new byte[]{5, 6}, file2);

      // write to two files works
      write(new byte[]{8, 9, 10, 11}, 0, storage);
      check(new byte[]{8, 9}, file1);
      check(new byte[]{10, 11}, file2);

      // make sure partial write into next file works
      write(new byte[]{100, 101, 102}, 0, storage);
      check(new byte[]{102, 11}, file2);
    } finally {
      storage.close();
    }
  }

  private void write(byte[] bytes, int offset, FileCollectionStorage storage) throws IOException {
    storage.write(ByteBuffer.wrap(bytes), offset);
    storage.finish();
  }

  private void check(byte[] bytes, File f) throws IOException {
    final byte[] temp = new byte[bytes.length];
    FileInputStream fileInputStream = new FileInputStream(f);
    final int totalRead;
    try {
      totalRead = fileInputStream.read(temp);
    } finally {
      fileInputStream.close();
    }
    assertEquals(totalRead, temp.length);
    assertEquals(temp, bytes);
  }
}