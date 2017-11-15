package com.turn.ttorrent.client.network;

import com.turn.ttorrent.common.Peer;

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

  /**
   * invoked when socket is connected to other peer
   * @param socketChannel connected channel
   * @param peer peer with which a connection is established
   */
  void onConnected(SocketChannel socketChannel, Peer peer);
}
