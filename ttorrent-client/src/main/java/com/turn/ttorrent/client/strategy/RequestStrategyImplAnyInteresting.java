package com.turn.ttorrent.client.strategy;

import com.turn.ttorrent.client.Piece;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Random;

public class RequestStrategyImplAnyInteresting implements RequestStrategy {

  private final Random myRandom = new Random();

  @Override
  public Piece choosePiece(BitSet interesting, Piece[] pieces) {
    List<Piece> onlyInterestingPieces = new ArrayList<Piece>();
    for (Piece p : pieces) {
      if (interesting.get(p.getIndex())) onlyInterestingPieces.add(p);
    }
    if (onlyInterestingPieces.isEmpty()) return null;
    return onlyInterestingPieces.get(myRandom.nextInt(onlyInterestingPieces.size()));
  }
}
