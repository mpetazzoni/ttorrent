package com.turn.ttorrent.common;

import java.util.List;

public interface TorrentMultiFileMetadata extends TorrentGeneralMetadata {

  /**
   * @return The filename of the directory in which to store all the files
   */
  String getDirectoryName();

  /**
   * @return list of files, stored in this torrent
   */
  List<TorrentFile> getFiles();

}
