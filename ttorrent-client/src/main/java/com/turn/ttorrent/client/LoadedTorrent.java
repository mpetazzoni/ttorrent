package com.turn.ttorrent.client;

import com.turn.ttorrent.client.storage.PieceStorage;
import com.turn.ttorrent.common.AnnounceableInformation;
import com.turn.ttorrent.common.TorrentHash;
import com.turn.ttorrent.common.TorrentMetadata;
import com.turn.ttorrent.common.TorrentStatistic;
import org.jetbrains.annotations.NotNull;

public interface LoadedTorrent {

  /**
   * @return {@link PieceStorage} where stored available pieces
   */
  PieceStorage getPieceStorage();

  /**
   * @return {@link TorrentMetadata} instance
   * @throws IllegalStateException if unable to fetch metadata from source
   *                               (e.g. source is .torrent file and it was deleted manually)
   */
  TorrentMetadata getMetadata() throws IllegalStateException;

  /**
   * @return new instance of {@link AnnounceableInformation} for announce this torrent to the tracker
   */
  @NotNull
  AnnounceableInformation createAnnounceableInformation();

  /**
   * @return {@link TorrentStatistic} instance related with this torrent
   */
  TorrentStatistic getTorrentStatistic();

  /**
   * @return hash of this torrent
   */
  TorrentHash getTorrentHash();

  /**
   * @return related {@link EventDispatcher}
   */
  EventDispatcher getEventDispatcher();

}
