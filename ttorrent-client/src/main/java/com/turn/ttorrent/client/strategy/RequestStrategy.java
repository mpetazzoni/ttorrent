package com.turn.ttorrent.client.strategy;

import com.turn.ttorrent.client.Piece;

import java.util.BitSet;

/**
 * Interface for a piece request strategy provider.
 *
 * @author cjmalloy
 */
public interface RequestStrategy {

  /**
   * Choose a piece from the remaining pieces.
   *
   * @param interesting A set of the index of all interesting pieces
   * @param pieces      The complete array of pieces
   * @return The chosen piece, or <code>null</code> if no piece is interesting
   */
  Piece choosePiece(BitSet interesting, Piece[] pieces);
}
