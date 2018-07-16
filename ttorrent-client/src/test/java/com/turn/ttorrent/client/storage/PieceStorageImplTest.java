package com.turn.ttorrent.client.storage;

import com.turn.ttorrent.client.ByteArrayStorage;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.BitSet;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class PieceStorageImplTest {

  private PieceStorage pieceStorage;
  private int pieceSize;
  private int pieceCount;
  private byte[] allPieces;

  @BeforeMethod
  public void setUp() throws IOException {

    pieceSize = 12;
    pieceCount = 8;
    ByteArrayStorage storage = new ByteArrayStorage(pieceSize * pieceCount);
    pieceStorage = new PieceStorageImpl(storage, new BitSet(), pieceCount, pieceSize);
    allPieces = new byte[pieceCount * pieceSize];
    for (byte i = 0; i < allPieces.length; i++) {
      allPieces[i] = i;
    }
  }

  @Test
  public void testStorage() throws IOException {

    assertEquals(pieceStorage.getAvailablePieces().cardinality(), 0);
    byte[] firstPieceData = Arrays.copyOfRange(allPieces, pieceSize, 2 * pieceSize);
    pieceStorage.savePiece(1, firstPieceData);
    byte[] thirdPieceData = Arrays.copyOfRange(allPieces, 3 * pieceSize, 4 * pieceSize);
    pieceStorage.savePiece(3, thirdPieceData);

    BitSet availablePieces = pieceStorage.getAvailablePieces();
    assertEquals(availablePieces.cardinality(), 2);
    assertTrue(availablePieces.get(1));
    assertTrue(availablePieces.get(3));

    byte[] actualFirstPieceData = pieceStorage.readPiecePart(1, 0, pieceSize);
    byte[] actualThirdPieceData = pieceStorage.readPiecePart(3, 0, pieceSize);
    assertEquals(actualFirstPieceData, firstPieceData);
    assertEquals(actualThirdPieceData, thirdPieceData);

    //check that reading by parts works correctly
    byte[] firstPiecePart = pieceStorage.readPiecePart(1, 0, pieceSize / 2);
    byte[] secondPiecePart = pieceStorage.readPiecePart(1, pieceSize / 2, pieceSize / 2);
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    outputStream.write(firstPiecePart);
    outputStream.write(secondPiecePart);
    assertEquals(firstPieceData, outputStream.toByteArray());

  }

  @Test
  public void testFullStorage() throws IOException {
    assertEquals(pieceStorage.getAvailablePieces().cardinality(), 0);
    for (int i = 0; i < pieceCount; i++) {
      assertEquals(pieceStorage.getAvailablePieces().cardinality(), i);
      pieceStorage.savePiece(i, Arrays.copyOfRange(allPieces, i * pieceSize, (i + 1) * pieceSize));
    }
    assertEquals(pieceStorage.getAvailablePieces().cardinality(), pieceCount);
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void testReadUnavailablePiece() throws IOException {
    pieceStorage.readPiecePart(45, 0, pieceSize);
  }

  @AfterMethod
  public void tearDown() throws IOException {
    pieceStorage.close();
  }
}
