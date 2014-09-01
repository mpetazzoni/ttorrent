package com.turn.ttorrent.client;

public interface PieceSelectionStrategy {
	
	public int compareTo(Piece first, Piece other);
	
}
