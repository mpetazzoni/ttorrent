package com.turn.ttorrent.client;

import java.io.*;

public class FileMetadataProvider implements TorrentMetadataProvider {

  private final String filePath;

  public FileMetadataProvider(String filePath) {
    this.filePath = filePath;
  }

  @Override
  public InputStream getTorrentMetadata() throws IOException {
    File file = new File(filePath);
    if (!file.isFile())
      throw new IllegalArgumentException("File " + filePath + " is not exist or is not a regular file");
    return new BufferedInputStream(new FileInputStream(file));
  }
}
