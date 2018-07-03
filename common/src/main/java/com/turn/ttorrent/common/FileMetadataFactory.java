package com.turn.ttorrent.common;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;

public class FileMetadataFactory implements TorrentMetadataFactory {

  private final String filePath;

  public FileMetadataFactory(String filePath) {
    this.filePath = filePath;
  }

  @NotNull
  @Override
  public TorrentMetadata getTorrentMetadata() throws IOException {
    File file = new File(filePath);
    return new TorrentParser().parseFromFile(file);
  }
}
