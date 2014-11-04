package com.turn.ttorrent.client.strategy;

import java.util.BitSet;
import java.util.SortedSet;

import com.turn.ttorrent.client.Piece;

/**
 * Interface for a piece request strategy provider.
 *
 * @author cjmalloy
 *
 */
public interface RequestStrategy {

	/**
	 * Choose a piece from the remaining pieces.
	 *
	 * @param rarest
	 *		  A set sorted by how rare the piece is
	 * @param interesting
	 *		  A set of the index of all interesting pieces
	 * @param pieces
	 *		  The complete array of pieces
	 *
	 * @return The chosen piece, or <code>null</code> if no piece is interesting
	 */
	Piece choosePiece(SortedSet<Piece> rarest, BitSet interesting, Piece[] pieces);
}
