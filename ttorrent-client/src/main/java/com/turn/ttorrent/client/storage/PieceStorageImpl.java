package com.turn.ttorrent.client.storage;

import com.turn.ttorrent.Constants;
import com.turn.ttorrent.common.TorrentFile;
import com.turn.ttorrent.common.TorrentMetadata;
import com.turn.ttorrent.common.TorrentUtils;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.BitSet;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class PieceStorageImpl implements PieceStorage {

  private final TorrentByteStorage fileCollectionStorage;
  private final ReadWriteLock readWriteLock;

  @Nullable
  private volatile BitSet availablePieces;
  private final int piecesCount;
  private final int pieceSize;
  private volatile boolean isOpen;

  public PieceStorageImpl(TorrentByteStorage fileCollectionStorage,
                          BitSet availablePieces,
                          int piecesCount,
                          int pieceSize) throws IOException {
    this.fileCollectionStorage = fileCollectionStorage;
    this.readWriteLock = new ReentrantReadWriteLock();
    this.piecesCount = piecesCount;
    this.pieceSize = pieceSize;
    BitSet bitSet = new BitSet(piecesCount);
    bitSet.or(availablePieces);
    if (bitSet.cardinality() != piecesCount) {
      this.availablePieces = bitSet;
      this.fileCollectionStorage.open(false);
    } else {
      this.fileCollectionStorage.open(true);
    }
    isOpen = true;
  }

  public static PieceStorageImpl createFromDirectoryAndMetadata(String downloadDirPath, TorrentMetadata metadata) throws IOException {

    File parent = new File(downloadDirPath);
    if (!parent.isDirectory()) {
      throw new IllegalArgumentException("Invalid parent directory!");
    }

    String parentPath = parent.getCanonicalPath();
    List<FileStorage> files = new LinkedList<FileStorage>();
    long offset = 0L;
    long totalSize = 0;
    boolean allFilesExists = true;
    for (TorrentFile file : metadata.getFiles()) {
      File actual = new File(parent, file.getRelativePathAsString());

      if (!actual.getCanonicalPath().startsWith(parentPath)) {
        throw new SecurityException("Torrent file path attempted " +
                "to break directory jail!");
      }

      actual.getParentFile().mkdirs();
      files.add(new FileStorage(actual, offset, file.size));
      allFilesExists = allFilesExists && actual.exists();
      offset += file.size;
      totalSize += file.size;
    }

    FileCollectionStorage fileCollectionStorage = new FileCollectionStorage(files, totalSize);
    fileCollectionStorage.open(allFilesExists);
    BitSet availablePieces = new BitSet(metadata.getPiecesCount());
    try {
      int pieceLength = metadata.getPieceLength();
      for (int i = 0; i < metadata.getPiecesCount(); i++) {
        long position = (long) i * pieceLength;
        int len = Math.min(
                (int) (totalSize - position),
                pieceLength);
        ByteBuffer buffer = ByteBuffer.allocate(len);
        fileCollectionStorage.read(buffer, position);
        byte[] expectedHash = Arrays.copyOfRange(metadata.getPiecesHashes(), i * Constants.PIECE_HASH_SIZE, (i + 1) * Constants.PIECE_HASH_SIZE);
        byte[] actualHash = TorrentUtils.calculateSha1Hash(buffer.array());
        if (Arrays.equals(expectedHash, actualHash)) {
          availablePieces.set(i);
        }
      }
    } finally {
      fileCollectionStorage.close();
    }

    return new PieceStorageImpl(
            fileCollectionStorage,
            availablePieces,
            metadata.getPiecesCount(),
            metadata.getPieceLength()
    );
  }

  private void checkPieceIndex(int pieceIndex) {
    if (pieceIndex < 0 || pieceIndex >= piecesCount) {
      throw new IllegalArgumentException("Incorrect piece index " + pieceIndex + ". Piece index must be positive less than" + piecesCount);
    }
  }

  @Override
  public synchronized void savePiece(int pieceIndex, byte[] pieceData) throws IOException {
    checkPieceIndex(pieceIndex);
    try {
      readWriteLock.writeLock().lock();

      BitSet availablePieces = this.availablePieces;

      boolean isFullyDownloaded = availablePieces == null;

      if (isFullyDownloaded) return;

      if (availablePieces.get(pieceIndex)) return;

      openStorageIsNecessary(false);

      long pos = pieceIndex;
      pos = pos * pieceSize;
      ByteBuffer buffer = ByteBuffer.wrap(pieceData);
      fileCollectionStorage.write(buffer, pos);

      availablePieces.set(pieceIndex);
      boolean isFullyNow = availablePieces.cardinality() == piecesCount;
      if (isFullyNow) {
        this.availablePieces = null;
        fileCollectionStorage.finish();
        fileCollectionStorage.close();
        fileCollectionStorage.open(true);
      }
    } finally {
      readWriteLock.writeLock().unlock();
    }
  }

  private void openStorageIsNecessary(boolean onlyRead) throws IOException {
    if (!isOpen) {
      fileCollectionStorage.open(onlyRead);
      isOpen = true;
    }
  }

  @Override
  public byte[] readPiecePart(int pieceIndex, int offset, int length) throws IOException {
    checkPieceIndex(pieceIndex);
    try {
      readWriteLock.readLock().lock();

      BitSet availablePieces = this.availablePieces;
      if (availablePieces != null && !availablePieces.get(pieceIndex)) {
        throw new IllegalArgumentException("trying reading part of not available piece");
      }

      openStorageIsNecessary(availablePieces == null);

      ByteBuffer buffer = ByteBuffer.allocate(length);
      long pos = pieceIndex;
      pos = pos * pieceSize + offset;
      fileCollectionStorage.read(buffer, pos);
      return buffer.array();
    } finally {
      readWriteLock.readLock().unlock();
    }
  }

  @Override
  public BitSet getAvailablePieces() {
    try {
      readWriteLock.readLock().lock();
      BitSet result = new BitSet(piecesCount);

      BitSet availablePieces = this.availablePieces;
      boolean isFullyDownloaded = availablePieces == null;

      if (isFullyDownloaded) {
        result.set(0, piecesCount);
        return result;
      }
      result.or(availablePieces);
      return result;
    } finally {
      readWriteLock.readLock().unlock();
    }
  }

  @Override
  public void close() throws IOException {
    try {
      readWriteLock.writeLock().lock();
      fileCollectionStorage.close();
      isOpen = false;
    } finally {
      readWriteLock.writeLock().unlock();
    }
  }

  @Override
  public void delete() throws IOException {
    close();
    fileCollectionStorage.delete();
  }
}
