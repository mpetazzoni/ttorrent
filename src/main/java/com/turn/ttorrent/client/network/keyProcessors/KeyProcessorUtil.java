package com.turn.ttorrent.client.network.keyProcessors;

import com.turn.ttorrent.client.network.KeyAttachment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

public class KeyProcessorUtil {

  private final static Logger logger = LoggerFactory.getLogger(KeyProcessorUtil.class);

  public static KeyAttachment getCastedAttachmentOrNull(SelectionKey key) {
    Object attachment = key.attachment();
    try {
      return (KeyAttachment) attachment;
    } catch (ClassCastException e) {
      logger.warn("unable to cast attachment {} to specified type", attachment);
    }
    return null;
  }

  public static SocketChannel getCastedChannelOrNull(SelectionKey key) {
    SelectableChannel channel = key.channel();
    try {
      return (SocketChannel) channel;
    } catch (ClassCastException e) {
      logger.warn("unable to cast channel {} to specified type");
    }
    return null;
  }
}
