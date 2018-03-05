package com.turn.ttorrent.common;

import java.io.IOException;
import java.io.InputStream;

public interface TorrentMetadataProvider {

  /**
   * Provide access to data of .torrent file
   *
   * @return stream which contains .torrent file content
   * @throws IOException if any io error occurs
   */
  InputStream getTorrentMetadata() throws IOException;

}
