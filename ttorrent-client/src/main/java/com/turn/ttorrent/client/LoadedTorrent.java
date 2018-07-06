package com.turn.ttorrent.client;

import com.turn.ttorrent.client.storage.PieceStorage;
import com.turn.ttorrent.common.AnnounceableInformation;
import com.turn.ttorrent.common.TorrentHash;
import com.turn.ttorrent.common.TorrentStatistic;
import org.jetbrains.annotations.NotNull;

public interface LoadedTorrent {

  /**
   * @return {@link PieceStorage} where stored available pieces
   */
  PieceStorage getPieceStorage();

  /**
   * @return path to .torrent file
   */
  String getDotTorrentFilePath();

  /**
   * @return new instance of {@link AnnounceableInformation} for announce this torrent to the tracker
   */
  @NotNull AnnounceableInformation createAnnounceableInformation();

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
