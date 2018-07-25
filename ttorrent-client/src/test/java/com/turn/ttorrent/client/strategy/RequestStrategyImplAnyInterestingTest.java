package com.turn.ttorrent.client.strategy;

import com.turn.ttorrent.client.Piece;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.BitSet;
import java.util.SortedSet;

public class RequestStrategyImplAnyInterestingTest {

  private final SortedSet<Piece> myRarest = null;//myRarest don't need for it strategy
  private final int myPiecesTotal = 10;
  private final Piece[] myPieces = new Piece[myPiecesTotal];
  private final RequestStrategy myRequestStrategy = new RequestStrategyImplAnyInteresting();

  @BeforeClass
  public void init() {
    for (int i = 0; i < myPieces.length; i++) {
      myPieces[i] = new Piece(null, i, 0, new byte[0]);
    }
  }

  @Test
  public void choosePieceNoInterestingTest() {
    Piece actual = myRequestStrategy.choosePiece(new BitSet(), myPieces);
    Assert.assertNull(actual);
  }

  @Test
  public void choosePieceOneInterestingTest() {
    BitSet interesting = new BitSet();
    for (int i = 0; i < myPieces.length; i++) {
      interesting.clear();
      interesting.set(i);
      Piece expected = myPieces[i];
      Piece actual = myRequestStrategy.choosePiece(interesting, myPieces);
      Assert.assertEquals(expected, actual);
    }
  }

  @Test
  public void choosePieceTest() {
    BitSet interesting = new BitSet();
    int interestingFrom = 1;
    int interestingTo = 5;
    interesting.set(interestingFrom, interestingTo);
    Piece actual = myRequestStrategy.choosePiece(interesting, myPieces);
    Assert.assertTrue(actual.getIndex() >= interestingFrom && actual.getIndex() <= interestingTo);
  }

}
