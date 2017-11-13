package com.turn.ttorrent.client.network;

import java.io.IOException;
import java.nio.channels.SocketChannel;

public interface ChannelListener {

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
   * @param socketChannel specified accepted socket channel
   * @throws IOException if an I/O error occurs
   */
  void onConnectionAccept(SocketChannel socketChannel) throws IOException;

}
