package com.turn.ttorrent.client.network;

import java.io.IOException;
import java.nio.channels.SocketChannel;

public interface DataProcessor {

  DataProcessor processAndGetNext(SocketChannel socketChannel) throws IOException;

}
