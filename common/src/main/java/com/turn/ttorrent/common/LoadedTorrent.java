package com.turn.ttorrent.common;

public interface LoadedTorrent extends AnnounceableInformation, TorrentStatisticProvider {

  /**
   * @return path to torrent root directory which contains files from torrent and is used for files downloading
   */
  String getDownloadDirPath();

  /**
   * @return path to .torrent file
   */
  String getDotTorrentFilePath();

  /**
   * @return true if it's fully seeder
   */
  boolean isSeeded();

  /**
   * @return true if and only if it's fully leacher
   */
  boolean isLeeched();

}
