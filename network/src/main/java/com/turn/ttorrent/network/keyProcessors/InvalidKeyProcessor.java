package com.turn.ttorrent.network.keyProcessors;

import com.turn.ttorrent.common.TorrentLoggerFactory;
import com.turn.ttorrent.network.ReadAttachment;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

public class InvalidKeyProcessor implements KeyProcessor {

  private final static Logger logger = TorrentLoggerFactory.getLogger();

  @Override
  public void process(SelectionKey key) throws IOException {
    final Object attachment = key.attachment();
    final SelectableChannel channel = key.channel();
    if (attachment == null) {
      key.cancel();
      return;
    }
    if (!(attachment instanceof ReadAttachment)) {
      key.cancel();
      return;
    }
    if (!(channel instanceof SocketChannel)) {
      key.cancel();
      return;
    }
    final SocketChannel socketChannel = (SocketChannel) channel;
    final ReadAttachment readAttachment = (ReadAttachment) attachment;

    logger.trace("drop invalid key {}", channel);
    readAttachment.getConnectionListener().onError(socketChannel, new CancelledKeyException());
  }

  @Override
  public boolean accept(SelectionKey key) {
    return !key.isValid();
  }
}
