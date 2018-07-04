package com.turn.ttorrent.common;

public interface LoadedTorrent {

  /**
   * @return path to torrent root directory which contains files from torrent and is used for files downloading
   */
  String getDownloadDirPath();

  /**
   * @return path to .torrent file
   */
  String getDotTorrentFilePath();

  /**
   * @return new instance of {@link AnnounceableInformation} for announce this torrent to the tracker
   */
  AnnounceableInformation createAnnounceableInformation();

  /**
   * @return true if it's fully seeder
   */
  boolean isSeeded();

  /**
   * @return true if and only if it's fully leacher
   */
  boolean isLeeched();

  /**
   * @return {@link TorrentStatistic} instance related with this torrent
   */
  TorrentStatistic getTorrentStatistic();

  /**
   * @return hash of this torrent
   */
  TorrentHash getTorrentHash();

}
