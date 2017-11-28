package com.turn.ttorrent.client.network.keyProcessors;

import com.turn.ttorrent.client.network.ChannelListenerFactory;
import com.turn.ttorrent.client.network.ConnectionListener;
import com.turn.ttorrent.client.network.KeyAttachment;
import com.turn.ttorrent.common.SystemTimeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.*;

public class AcceptableKeyProcessor implements KeyProcessor {

  private static final Logger logger = LoggerFactory.getLogger(AcceptableKeyProcessor.class);

  private final Selector mySelector;
  private final String myServerSocketLocalAddress;

  public AcceptableKeyProcessor(Selector selector, String serverSocketLocalAddress) {
    this.mySelector = selector;
    this.myServerSocketLocalAddress = serverSocketLocalAddress;
  }

  @Override
  public void process(SelectionKey key) throws IOException {
    SelectableChannel channel = key.channel();
    if (!(channel instanceof ServerSocketChannel)) {
      logger.error("incorrect instance of server channel. Can not accept connections");
      key.cancel();
      return;
    }
    Object attachment = key.attachment();
    if (!(attachment instanceof ChannelListenerFactory)) {
      logger.error("incorrect instance of server channel key attachment");
      key.cancel();
      return;
    }
    ChannelListenerFactory channelListenerFactory = (ChannelListenerFactory) attachment;

    SocketChannel socketChannel = ((ServerSocketChannel) key.channel()).accept();
    logger.trace("server {} get new connection from {}", new Object[]{myServerSocketLocalAddress, socketChannel.socket()});

    ConnectionListener stateConnectionListener = channelListenerFactory.newChannelListener();
    stateConnectionListener.onConnectionEstablished(socketChannel);
    socketChannel.configureBlocking(false);
    KeyAttachment keyAttachment = new KeyAttachment(stateConnectionListener, new SystemTimeService());
    socketChannel.register(mySelector, SelectionKey.OP_READ, keyAttachment);
  }

  @Override
  public boolean accept(SelectionKey key) {
    return key.isValid() && key.isAcceptable();
  }
}
