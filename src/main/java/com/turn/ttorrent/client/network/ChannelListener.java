package com.turn.ttorrent.client.network;

import java.io.IOException;
import java.nio.channels.SocketChannel;

public interface ChannelListener {

  void onNewDataAvailable(SocketChannel socketChannel) throws IOException;

  void onConnectionAccept(SocketChannel socketChannel) throws IOException;

}
