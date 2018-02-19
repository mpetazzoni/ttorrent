package com.turn.ttorrent.common;

public interface TorrentsStorageProvider {

  /**
   * @return instance of torrents storage
   */
  TorrentsStorage getTorrentsStorage();

}
