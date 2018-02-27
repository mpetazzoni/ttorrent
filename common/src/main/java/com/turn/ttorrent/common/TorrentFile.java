package com.turn.ttorrent.common;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * @author dgiffin
 * @author mpetazzoni
 */
public class TorrentFile {

  @NotNull
  public final List<String> relativePath;
  public final long size;
  @NotNull
  public final Optional<String> md5Hash;

  public TorrentFile(@NotNull List<String> relativePath, long size, @Nullable String md5Hash) {
    this.relativePath = new ArrayList<String>(relativePath);
    this.size = size;
    this.md5Hash = Optional.of(md5Hash);
  }

  public String getRelativePathAsString() {
    String delimiter = File.separator;
    final Iterator<String> iterator = relativePath.iterator();
    StringBuilder sb = new StringBuilder();
    if (iterator.hasNext()) {
      sb.append(iterator.next());
      while (iterator.hasNext()) {
        sb.append(delimiter).append(iterator.next());
      }
    }
    return sb.toString();
  }

}
