package com.turn.ttorrent.network.keyProcessors;

import com.turn.ttorrent.common.TimeService;
import com.turn.ttorrent.common.TorrentLoggerFactory;
import com.turn.ttorrent.network.*;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.channels.*;
import java.util.concurrent.atomic.AtomicInteger;

public class AcceptableKeyProcessor implements KeyProcessor {

  private static final Logger logger = TorrentLoggerFactory.getLogger();

  private final Selector mySelector;
  private final String myServerSocketLocalAddress;
  private final TimeService myTimeService;
  private final NewConnectionAllower myNewConnectionAllower;
  private final TimeoutStorage myTimeoutStorage;
  private final AtomicInteger mySendBufferSize;
  private final AtomicInteger myReceiveBufferSize;

  public AcceptableKeyProcessor(Selector selector,
                                String serverSocketLocalAddress,
                                TimeService timeService,
                                NewConnectionAllower newConnectionAllower,
                                TimeoutStorage timeoutStorage,
                                AtomicInteger sendBufferSize,
                                AtomicInteger receiveBufferSize) {
    this.mySelector = selector;
    this.myServerSocketLocalAddress = serverSocketLocalAddress;
    this.myTimeService = timeService;
    this.myNewConnectionAllower = newConnectionAllower;
    this.myTimeoutStorage = timeoutStorage;
    this.mySendBufferSize = sendBufferSize;
    this.myReceiveBufferSize = receiveBufferSize;
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
    KeyProcessorUtil.setBuffersSizeIfNecessary(socketChannel, mySendBufferSize.get(), myReceiveBufferSize.get());
    ReadWriteAttachment keyAttachment = new ReadWriteAttachment(stateConnectionListener, myTimeService.now(), myTimeoutStorage.getTimeoutMillis());
    socketChannel.register(mySelector, SelectionKey.OP_READ, keyAttachment);
  }

  @Override
  public boolean accept(SelectionKey key) {
    return key.isValid() && key.isAcceptable();
  }
}
