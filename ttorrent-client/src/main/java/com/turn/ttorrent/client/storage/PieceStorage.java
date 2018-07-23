package com.turn.ttorrent.client.storage;

import java.io.Closeable;
import java.io.IOException;
import java.util.BitSet;

public interface PieceStorage extends Closeable {

  void savePiece(int pieceIndex, byte[] pieceData) throws IOException;

  byte[] readPiecePart(int pieceIndex, int offset, int length) throws IOException;

  BitSet getAvailablePieces();

  boolean isFinished();

}
