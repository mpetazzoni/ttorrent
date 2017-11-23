package com.turn.ttorrent.client.network;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.*;

public class AcceptableKeyProcessor implements KeyProcessor {

  private static final Logger logger = LoggerFactory.getLogger(AcceptableKeyProcessor.class);

  private final ChannelListenerFactory myChannelListenerFactory;
  private final Selector mySelector;
  private final String myServerSocketLocalAddress;

  public AcceptableKeyProcessor(ChannelListenerFactory channelListenerFactory, Selector selector, String serverSocketLocalAddress) {
    this.myChannelListenerFactory = channelListenerFactory;
    this.mySelector = selector;
    this.myServerSocketLocalAddress = serverSocketLocalAddress;
  }

  @Override
  public void process(SelectionKey key) throws IOException {
    SelectableChannel channel = key.channel();
    if (!(channel instanceof ServerSocketChannel)) {
      logger.error("incorrect instance of server channel. Can not accept connections");
      channel.close();
      return;
    }
    SocketChannel socketChannel = ((ServerSocketChannel) key.channel()).accept();
    logger.trace("server {} get new connection from {}", new Object[]{myServerSocketLocalAddress, socketChannel.socket()});

    ConnectionListener stateConnectionListener = myChannelListenerFactory.newChannelListener();
    stateConnectionListener.onConnectionEstablished(socketChannel);
    socketChannel.configureBlocking(false);
    socketChannel.register(mySelector, SelectionKey.OP_READ, stateConnectionListener);
  }
}
