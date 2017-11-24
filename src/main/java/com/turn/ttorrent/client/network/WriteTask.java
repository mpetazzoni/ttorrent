package com.turn.ttorrent.client.network;

import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

public class WriteTask {

  private final SocketChannel socketChannel;
  private final ByteBuffer byteBuffer;
  private final WriteListener listener;

  public WriteTask(SocketChannel socketChannel, ByteBuffer byteBuffer, WriteListener listener) {
    this.socketChannel = socketChannel;
    this.byteBuffer = byteBuffer;
    this.listener = listener;
  }

  public SocketChannel getSocketChannel() {
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
