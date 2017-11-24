package com.turn.ttorrent.client.network;

import java.io.IOException;
import java.nio.channels.ByteChannel;
import java.nio.channels.SocketChannel;

public interface DataProcessor {

  /**
   * the method must read data from channel and process it
   *
   * @param socketChannel specified socket channel with data
   * @return data processor which must process next data
   * @throws IOException if an I/O error occurs
   */
  DataProcessor processAndGetNext(ByteChannel socketChannel) throws IOException;

}
