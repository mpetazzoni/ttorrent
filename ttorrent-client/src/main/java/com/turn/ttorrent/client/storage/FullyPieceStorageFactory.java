package com.turn.ttorrent.client.storage;

import com.turn.ttorrent.common.TorrentMetadata;

import java.util.BitSet;

public class FullyPieceStorageFactory implements PieceStorageFactory {

  public final static FullyPieceStorageFactory INSTANCE = new FullyPieceStorageFactory();

  private FullyPieceStorageFactory() {
  }

  @Override
  public PieceStorage createStorage(TorrentMetadata metadata, TorrentByteStorage byteStorage) {

    BitSet availablePieces = new BitSet(metadata.getPiecesCount());
    availablePieces.set(0, metadata.getPiecesCount());
    return new PieceStorageImpl(
            byteStorage,
            availablePieces,
            metadata.getPiecesCount(),
            metadata.getPieceLength()
    );
  }
}
