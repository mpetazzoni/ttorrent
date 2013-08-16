package com.turn.ttorrent;

import java.io.*;
import java.util.*;

public class TempFiles {
  private static final File ourCurrentTempDir = FileUtil.getTempDirectory();
  private final File myCurrentTempDir;

  private static Random ourRandom;

  static {
    ourRandom = new Random();
    ourRandom.setSeed(System.currentTimeMillis());
  }

  private final List<File> myFilesToDelete = new ArrayList<File>();
  private final Thread myShutdownHook;
  private volatile boolean myInsideShutdownHook;

  public TempFiles() {
    myCurrentTempDir = ourCurrentTempDir;
    if (!myCurrentTempDir.isDirectory()) {

      throw new IllegalStateException("Temp directory is not a directory, was deleted by some process: " + myCurrentTempDir.getAbsolutePath() +
          "\njava.io.tmpdir: " + FileUtil.getTempDirectory());
    }

    myShutdownHook = new Thread(new Runnable() {
      public void run() {
        myInsideShutdownHook = true;
        cleanup();
      }
    });
    Runtime.getRuntime().addShutdownHook(myShutdownHook);
  }

  private File doCreateTempDir(String prefix, String suffix) throws IOException {
    prefix = prefix == null ? "" : prefix;
    suffix = suffix == null ? ".tmp" : suffix;

    do {
      int count = ourRandom.nextInt();
      final File f = new File(myCurrentTempDir, prefix + count + suffix);
      if (!f.exists() && f.mkdirs()) {
        return f.getCanonicalFile();
      }
    } while (true);

  }
  private File doCreateTempFile(String prefix, String suffix) throws IOException {
    final File file = doCreateTempDir(prefix, suffix);
    file.delete();
    file.createNewFile();
    return file;
  }

  public final File createTempFile(String content) throws IOException {
    File tempFile = createTempFile();
    FileUtil.writeFile(tempFile, content);
    return tempFile;
  }

  public final File createTempFile() throws IOException {
    File tempFile = doCreateTempFile("test", null);
    registerAsTempFile(tempFile);
    return tempFile;
  }

  public void registerAsTempFile(final File tempFile) {
    myFilesToDelete.add(tempFile);
  }

  public final File createTempFile(int size) throws IOException {
    File tempFile = createTempFile();
    int bufLen = Math.min(8 * 1024, size);
    if (bufLen == 0) return tempFile;
    final OutputStream fos = new BufferedOutputStream(new FileOutputStream(tempFile));
    try {
      byte[] buf = new byte[bufLen];
      for (int i=0; i < buf.length; i++) {
        buf[i] = (byte)Math.round(Math.random()*128);
      }

      int numWritten = 0;
      for (int i=0; i<size / buf.length; i++) {
        fos.write(buf);
        numWritten += buf.length;
      }

      if (size > numWritten) {
        fos.write(buf, 0, size - numWritten);
      }
    } finally {
      fos.close();
    }

    return tempFile;
  }

  /**
   * Returns a File object for created temp directory.
   * Also stores the value into this object accessed with {@link #getCurrentTempDir()}
   *
   * @return a File object for created temp directory
   * @throws IOException if directory creation fails.
   */
  public final File createTempDir() throws IOException {
    File f = doCreateTempDir("test", "");
    registerAsTempFile(f);
    return f;
  }

  /**
   * Returns the current directory used by the test or null if no test is running or no directory is created yet.
   *
   * @return see above
   */
  public File getCurrentTempDir() {
    return myCurrentTempDir;
  }

  public void cleanup() {
    try {
      for (File file : myFilesToDelete) {
        FileUtil.delete(file);
      }

      myFilesToDelete.clear();
    } finally {
      if (!myInsideShutdownHook) {
        Runtime.getRuntime().removeShutdownHook(myShutdownHook);
      }
    }
  }
}