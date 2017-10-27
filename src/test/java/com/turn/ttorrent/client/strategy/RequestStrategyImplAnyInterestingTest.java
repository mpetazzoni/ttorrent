package com.turn.ttorrent.client.strategy;

import com.turn.ttorrent.client.Piece;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.BitSet;
import java.util.SortedSet;

public class RequestStrategyImplAnyInterestingTest {

    final SortedSet<Piece> rarest = null;//rarest don't need for it strategy
    final int piecesTotal = 10;
    final Piece[] pieces = new Piece[piecesTotal];
    final RequestStrategy requestStrategy = new RequestStrategyImplAnyInteresting();

    @BeforeClass
    public void init() {
        for (int i = 0; i < pieces.length; i++) {
            pieces[i] = new Piece(null, i, 0, 0, new byte[0], false);
        }
    }

    @Test
    public void choosePieceNoInterestingTest() {
        Piece actual = requestStrategy.choosePiece(rarest, new BitSet(), pieces);
        Assert.assertNull(actual);
    }

    @Test
    public void choosePieceOneInterestingTest() {
        BitSet interesting = new BitSet();
        for (int i = 0; i < pieces.length; i++) {
            interesting.clear();
            interesting.set(i);
            Piece expected = pieces[i];
            Piece actual = requestStrategy.choosePiece(rarest, interesting, pieces);
            Assert.assertEquals(expected, actual);
        }
    }

    @Test
    public void choosePieceTest() {
        BitSet interesting = new BitSet();
        int interestingFrom = 1;
        int interestingTo = 5;
        interesting.set(interestingFrom, interestingTo);
        Piece actual = requestStrategy.choosePiece(rarest, interesting, pieces);
        Assert.assertTrue(actual.getIndex() >= interestingFrom && actual.getIndex() <= interestingTo);
    }

}
