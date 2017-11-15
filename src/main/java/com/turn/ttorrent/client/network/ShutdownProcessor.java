package com.turn.ttorrent.client.network;

import java.io.IOException;
import java.nio.channels.ByteChannel;
import java.nio.channels.SocketChannel;

public class ShutdownProcessor implements DataProcessor {

  @Override
  public DataProcessor processAndGetNext(ByteChannel socketChannel) throws IOException {
    if (socketChannel.isOpen())
      socketChannel.close();
    return this;
  }
}
