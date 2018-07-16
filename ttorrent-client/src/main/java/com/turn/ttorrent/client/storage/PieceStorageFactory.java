package com.turn.ttorrent.client.storage;

import com.turn.ttorrent.common.TorrentMetadata;

import java.io.IOException;

public interface PieceStorageFactory {

  /**
   * create new {@link PieceStorage} for specified torrent with specified byte storage
   *
   * @param metadata    specified metadata
   * @param byteStorage specified byte storage where will be stored pieces
   * @return new {@link PieceStorage}
   */
  PieceStorage createStorage(TorrentMetadata metadata, TorrentByteStorage byteStorage) throws IOException;

}
