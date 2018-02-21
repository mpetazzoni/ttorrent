package com.turn.ttorrent.network;

import java.io.IOException;
import java.nio.channels.SocketChannel;

public interface ConnectionListener {

  /**
   * invoked when specified socket channel contains any data
   *
   * @param socketChannel specified socket channel with data
   * @throws IOException if an I/O error occurs
   */
  void onNewDataAvailable(SocketChannel socketChannel) throws IOException;

  /**
   * invoked when get new connection
   *
   * @param socketChannel specified socket channel
   * @throws IOException if an I/O error occurs
   */
  void onConnectionEstablished(SocketChannel socketChannel) throws IOException;

  /**
   * invoked when an error occurs
   *
   * @param socketChannel specified channel, associated with this channel
   * @param ex            specified exception
   * @throws IOException if an I/O error occurs
   */
  void onError(SocketChannel socketChannel, Throwable ex) throws IOException;
}
