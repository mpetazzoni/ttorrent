package com.turn.ttorrent.client;

public class RareFirstStrategy implements PieceSelectionStrategy {

	@Override
	public int compareTo(Piece first, Piece other) {
		if (first.getSeen() != other.getSeen()) {
			return first.getSeen() < other.getSeen() ? -1 : 1;
		}
		return first.getIndex() == other.getIndex() ? 0 :
			(first.getIndex() < other.getIndex() ? -1 : 1);
	}

}
