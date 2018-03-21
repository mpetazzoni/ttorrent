package com.turn.ttorrent.common;

public interface AnnounceableFileTorrent extends AnnounceableTorrent, TorrentStatisticProvider, FileTorrent {

  /**
   * @return true if it's fully seeder
   */
  boolean isSeeded();

  /**
   * @return true if and only if it's fully leacher
   */
  boolean isLeeched();

}
