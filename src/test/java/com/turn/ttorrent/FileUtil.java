package com.turn.ttorrent;

import java.io.Closeable;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

public class FileUtil {

  public static File getTempDirectory() {
    return new File(System.getProperty("java.io.tmpdir"));
  }

  public static void delete(File file) {
    if (file.isDirectory()) {
      File[] files = file.listFiles();
      for (File f: files) {
        delete(f);
      }
    }
    deleteFile(file);
  }

  private static boolean deleteFile(File file) {
    if (!file.exists()) return false;
    for (int i=0; i<10; i++) {
      if (file.delete()) return true;
      try {
        Thread.sleep(1);
      } catch (InterruptedException e) {
        //
      }
    }
    return false;
  }

  public static void writeFile(File file, String content) throws IOException {
    FileWriter fw = null;
    try {
      fw = new FileWriter(file);
      fw.write(content);
    } finally {
      close(fw);
    }
  }

  public static void close(Closeable closeable) {
    if (closeable != null) {
      try {
        closeable.close();
      } catch (IOException e) {
        //
      }
    }
  }
}
