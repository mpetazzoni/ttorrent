package com.turn.ttorrent.client.storage;

import com.turn.ttorrent.common.TorrentFile;
import com.turn.ttorrent.common.TorrentMetadata;
import org.jetbrains.annotations.NotNull;
import org.testng.annotations.Test;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

@Test
public class FairPieceStorageFactoryTest {

  public void testCreatingStorageForLargeFile() throws Exception {


    int pieceLength = 1024 * 1024;
    int pieceCount = 3000;

    //last piece can have not fully size
    final int lastPieceLength = pieceLength / 2;
    long totalSize = (long) (pieceCount - 1) * pieceLength + lastPieceLength;

    TorrentMetadata metadata = mock(TorrentMetadata.class);
    when(metadata.getPieceLength()).thenReturn(pieceLength);
    when(metadata.getPiecesCount()).thenReturn(pieceCount);
    when(metadata.getFiles()).thenReturn(Collections.singletonList(new TorrentFile(Collections.singletonList("test.avi"), totalSize, "")));
    when(metadata.getPiecesHashes()).thenReturn(new byte[pieceCount * 20]);

    final AtomicBoolean isReadInvokedForLastPiece = new AtomicBoolean(false);
    TorrentByteStorage storage = new TorrentByteStorage() {
      @Override
      public void open(boolean seeder) {

      }

      @Override
      public int read(ByteBuffer buffer, long position) {
        if (buffer.capacity() == lastPieceLength) {
          isReadInvokedForLastPiece.set(true);
        }
        buffer.putInt(1);
        return 1;
      }

      @Override
      public int write(ByteBuffer block, long position) {
        throw notImplemented();
      }

      @NotNull
      private RuntimeException notImplemented() {
        return new RuntimeException("notImplemented");
      }

      @Override
      public void finish() {
        throw notImplemented();
      }

      @Override
      public boolean isFinished() {
        throw notImplemented();
      }

      @Override
      public void delete() {
        throw notImplemented();
      }

      @Override
      public void close() {

      }
    };

    PieceStorage pieceStorage = FairPieceStorageFactory.INSTANCE.createStorage(metadata, storage);

    assertTrue(isReadInvokedForLastPiece.get());
    assertEquals(0, pieceStorage.getAvailablePieces().cardinality());
    assertFalse(pieceStorage.isFinished());
  }

}
