package com.turn.ttorrent.client.storage;

import com.turn.ttorrent.Constants;
import com.turn.ttorrent.common.TorrentFile;
import com.turn.ttorrent.common.TorrentMetadata;
import com.turn.ttorrent.common.TorrentUtils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.BitSet;

/**
 * This implementation will read all pieces from storage and compare hashes of pieces with really hashed
 * from metadata
 */
public class FairPieceStorageFactory implements PieceStorageFactory {

  public final static FairPieceStorageFactory INSTANCE = new FairPieceStorageFactory();

  private FairPieceStorageFactory() {
  }

  @Override
  public PieceStorage createStorage(TorrentMetadata metadata, TorrentByteStorage byteStorage) throws IOException {
    long totalSize = 0;
    for (TorrentFile file : metadata.getFiles()) {
      totalSize += file.size;
    }

    byteStorage.open(false);
    BitSet availablePieces = new BitSet(metadata.getPiecesCount());
    try {
      int pieceLength = metadata.getPieceLength();
      for (int i = 0; i < metadata.getPiecesCount(); i++) {
        long position = (long) i * pieceLength;
        int len = Math.min(
                (int) (totalSize - position),
                pieceLength);
        ByteBuffer buffer = ByteBuffer.allocate(len);
        byteStorage.read(buffer, position);
        byte[] expectedHash = Arrays.copyOfRange(metadata.getPiecesHashes(), i * Constants.PIECE_HASH_SIZE, (i + 1) * Constants.PIECE_HASH_SIZE);
        byte[] actualHash = TorrentUtils.calculateSha1Hash(buffer.array());
        if (Arrays.equals(expectedHash, actualHash)) {
          availablePieces.set(i);
        }
      }
    } finally {
      byteStorage.close();
    }

    return new PieceStorageImpl(
            byteStorage,
            availablePieces,
            metadata.getPiecesCount(),
            metadata.getPieceLength()
    );
  }
}
