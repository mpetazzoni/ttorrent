package com.turn.ttorrent.client.network.keyProcessors;

import com.turn.ttorrent.client.network.*;
import com.turn.ttorrent.common.TimeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;

import static com.turn.ttorrent.TorrentDefaults.SOCKET_CONNECTION_TIMEOUT_MILLIS;

public class ConnectableKeyProcessor implements KeyProcessor {

  private static final Logger logger = LoggerFactory.getLogger(ConnectableKeyProcessor.class);

  private final Selector mySelector;
  private final TimeService myTimeService;

  public ConnectableKeyProcessor(Selector selector, TimeService timeService) {
    this.mySelector = selector;
    this.myTimeService = timeService;
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
    Object attachment = key.attachment();
    if (!(attachment instanceof ConnectTask)) {
      logger.warn("incorrect instance of attachment for channel {}. The key for the channel is cancelled", socketChannel);
      key.cancel();
      return;
    }
    boolean isConnectFinished = socketChannel.finishConnect();
    if (!isConnectFinished) {
      return;
    }
    socketChannel.configureBlocking(false);
    ConnectionListener connectionListener = ((ConnectTask) attachment).getConnectionListener();
    ReadWriteAttachment keyAttachment = new ReadWriteAttachment(connectionListener, myTimeService.now(), SOCKET_CONNECTION_TIMEOUT_MILLIS);
    socketChannel.register(mySelector, SelectionKey.OP_READ, keyAttachment);
    connectionListener.onConnectionEstablished(socketChannel);
  }

  @Override
  public boolean accept(SelectionKey key) {
    return key.isValid() && key.isConnectable();
  }
}
