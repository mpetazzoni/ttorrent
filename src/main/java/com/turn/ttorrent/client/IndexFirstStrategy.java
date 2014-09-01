package com.turn.ttorrent.client;

public class IndexFirstStrategy implements PieceSelectionStrategy {

	@Override
	public int compareTo(Piece first, Piece other) {
		return first.getIndex() == other.getIndex() ? 0 :
			(first.getIndex() < other.getIndex() ? -1 : 1);
	}

}
