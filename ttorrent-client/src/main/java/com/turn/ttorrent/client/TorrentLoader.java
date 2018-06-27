package com.turn.ttorrent.client;

import com.turn.ttorrent.common.AnnounceableFileTorrent;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

public interface TorrentLoader {

  /**
   * Creates or finds shared torrent instance for specified announceable torrent and return it
   *
   * @param announceableFileTorrent specified torrent
   * @return shared torrent instance associated with current announceable torrent
   * @throws IOException              if any io error occurs
   */
  @NotNull
  SharedTorrent loadTorrent(@NotNull AnnounceableFileTorrent announceableFileTorrent) throws IOException;

}
