package com.turn.ttorrent.client.network.keyProcessors;

import com.turn.ttorrent.client.network.*;
import com.turn.ttorrent.common.TimeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.*;

import static com.turn.ttorrent.TorrentDefaults.SOCKET_CONNECTION_TIMEOUT_MILLIS;

public class AcceptableKeyProcessor implements KeyProcessor {

  private static final Logger logger = LoggerFactory.getLogger(AcceptableKeyProcessor.class);

  private final Selector mySelector;
  private final String myServerSocketLocalAddress;
  private final TimeService myTimeService;
  private final NewConnectionAllower myNewConnectionAllower;

  public AcceptableKeyProcessor(Selector selector,
                                String serverSocketLocalAddress,
                                TimeService timeService,
                                NewConnectionAllower newConnectionAllower) {
    this.mySelector = selector;
    this.myServerSocketLocalAddress = serverSocketLocalAddress;
    this.myTimeService = timeService;
    this.myNewConnectionAllower = newConnectionAllower;
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
    if (!(attachment instanceof AcceptAttachment)) {
      logger.error("incorrect instance of server channel key attachment");
      key.cancel();
      return;
    }
    ChannelListenerFactory channelListenerFactory = ((AcceptAttachment) attachment).getChannelListenerFactory();

    SocketChannel socketChannel = ((ServerSocketChannel) key.channel()).accept();
    logger.trace("server {} get new connection from {}", new Object[]{myServerSocketLocalAddress, socketChannel.socket()});

    if (!myNewConnectionAllower.isNewConnectionAllowed()) {
      logger.info("new connection is not allowed. New connection is closed");
      socketChannel.close();
      return;
    }

    ConnectionListener stateConnectionListener = channelListenerFactory.newChannelListener();
    stateConnectionListener.onConnectionEstablished(socketChannel);
    socketChannel.configureBlocking(false);
    ReadWriteAttachment keyAttachment = new ReadWriteAttachment(stateConnectionListener, myTimeService.now(), SOCKET_CONNECTION_TIMEOUT_MILLIS);
    socketChannel.register(mySelector, SelectionKey.OP_READ, keyAttachment);
  }

  @Override
  public boolean accept(SelectionKey key) {
    return key.isValid() && key.isAcceptable();
  }
}
