package com.turn.ttorrent.network;

import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;

public class WriteTask {

  private final ByteChannel socketChannel;
  private final ByteBuffer byteBuffer;
  private final WriteListener listener;

  public WriteTask(ByteChannel socketChannel, ByteBuffer byteBuffer, WriteListener listener) {
    this.socketChannel = socketChannel;
    this.byteBuffer = byteBuffer;
    this.listener = listener;
  }

  public ByteChannel getSocketChannel() {
    return socketChannel;
  }

  public ByteBuffer getByteBuffer() {
    return byteBuffer;
  }

  public WriteListener getListener() {
    return listener;
  }

  @Override
  public String toString() {
    return "WriteTask{" +
            "socketChannel=" + socketChannel +
            ", byteBuffer=" + byteBuffer +
            ", listener=" + listener +
            '}';
  }
}
