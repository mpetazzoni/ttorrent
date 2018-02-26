package com.turn.ttorrent.common;

public interface FileTorrent {

  /**
   * @return path to torrent root directory which contains files from torrent and is used for files downloading
   */
  String getDownloadDirPath();

  /**
   * @return path to .torrent file
   */
  String getDotTorrentFilePath();

}
