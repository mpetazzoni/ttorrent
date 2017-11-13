package com.turn.ttorrent.common;

public interface TorrentsStorageFactory {

  /**
   * @return instance of torrents storage
   */
  TorrentsStorage getTorrentsStorage();

}
