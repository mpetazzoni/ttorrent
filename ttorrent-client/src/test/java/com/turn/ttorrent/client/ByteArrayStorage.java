package com.turn.ttorrent.client;

import com.turn.ttorrent.client.storage.TorrentByteStorage;

import java.nio.ByteBuffer;
import java.util.Arrays;

public class ByteArrayStorage implements TorrentByteStorage {

  private final byte[] array;
  private boolean finished = false;

  public ByteArrayStorage(int maxSize) {
    array = new byte[maxSize];
  }

  @Override
  public void open(boolean seeder) {
  }

  private int intPosition(long position) {
    if (position > Integer.MAX_VALUE || position < 0) {
      throw new IllegalArgumentException("Position is too large");
    }
    return (int) position;
  }

  @Override
  public int read(ByteBuffer buffer, long position) {

    int pos = intPosition(position);
    int bytesCount = buffer.remaining();
    buffer.put(Arrays.copyOfRange(array, pos, pos + bytesCount));
    return bytesCount;
  }

  @Override
  public int write(ByteBuffer block, long position) {
    int pos = intPosition(position);
    int bytesCount = block.remaining();
    byte[] toWrite = new byte[bytesCount];
    block.get(toWrite);
    System.arraycopy(toWrite, 0, array, pos, toWrite.length);
    return bytesCount;
  }

  @Override
  public void finish() {
    finished = true;
  }

  @Override
  public boolean isFinished() {
    return finished;
  }

  @Override
  public void delete() {
  }

  @Override
  public void close() {
  }
}
