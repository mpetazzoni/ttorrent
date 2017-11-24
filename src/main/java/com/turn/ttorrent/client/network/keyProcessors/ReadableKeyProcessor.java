package com.turn.ttorrent.client.network.keyProcessors;

import com.turn.ttorrent.client.network.ConnectionListener;
import com.turn.ttorrent.client.network.ConnectionManager;
import com.turn.ttorrent.client.network.KeyAttachment;
import com.turn.ttorrent.client.network.keyProcessors.KeyProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

public class ReadableKeyProcessor implements KeyProcessor {

  private static final Logger logger = LoggerFactory.getLogger(ReadableKeyProcessor.class);

  private final String myServerSocketLocalAddress;

  public ReadableKeyProcessor(String serverSocketLocalAddress) {
    this.myServerSocketLocalAddress = serverSocketLocalAddress;
  }

  @Override
  public void process(SelectionKey key) throws IOException {
    SelectableChannel channel = key.channel();
    if (!(channel instanceof SocketChannel)) {
      logger.warn("incorrect instance of channel. The key is cancelled");
      key.cancel();
      return;
    }

    SocketChannel socketChannel = (SocketChannel) channel;
    logger.trace("server {} get new data from {}", myServerSocketLocalAddress, socketChannel);

    Object attachment = key.attachment();
    if (!(attachment instanceof KeyAttachment)) {
      logger.warn("incorrect instance of attachment for channel {}", new Object[]{socketChannel.socket()});
      socketChannel.close();
      return;
    }
    ConnectionListener connectionListener = ((KeyAttachment) attachment).getConnectionListener();
    connectionListener.onNewDataAvailable(socketChannel);
  }

  @Override
  public boolean accept(SelectionKey key) {
    return key.isValid() && key.isReadable();
  }
}
