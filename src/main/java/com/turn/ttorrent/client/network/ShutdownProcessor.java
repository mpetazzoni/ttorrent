package com.turn.ttorrent.client.network;

import java.io.IOException;
import java.nio.channels.SocketChannel;

public class ShutdownProcessor implements DataProcessor {

  @Override
  public DataProcessor processAndGetNext(SocketChannel socketChannel) throws IOException {
    if (socketChannel.isOpen())
      socketChannel.close();
    return this;
  }
}
