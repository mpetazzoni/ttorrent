package com.turn.ttorrent.client.network;

import java.io.IOException;
import java.nio.channels.ByteChannel;

public interface DataProcessor {

  /**
   * the method must read data from channel and process it
   *
   * @param socketChannel specified socket channel with data
   * @return data processor which must process next data
   * @throws IOException if an I/O error occurs
   */
  DataProcessor processAndGetNext(ByteChannel socketChannel) throws IOException;

  /**
   * the method must handle error and correctly release resources
   *
   * @param socketChannel specified channel
   * @param e             specified exception
   * @return data processor which must process next error. Can be null
   * @throws IOException if an I/O error occurs
   */
  DataProcessor handleError(ByteChannel socketChannel, Throwable e) throws IOException;

}
