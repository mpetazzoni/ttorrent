package com.turn.ttorrent.client.network.keyProcessors;

import com.turn.ttorrent.client.network.KeyAttachment;
import org.slf4j.Logger;

import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

public class KeyProcessorUtil {

  public static KeyAttachment getCastedAttachmentOrNull(SelectionKey key, Logger logger) {
    Object attachment = key.attachment();
    try {
      return (KeyAttachment) attachment;
    } catch (ClassCastException e) {
      logger.warn("unable to cast attachment {} to specified type");
    }
    return null;
  }

  public static SocketChannel getCastedChannelOrNull(SelectionKey key, Logger logger) {
    SelectableChannel channel = key.channel();
    try {
      return (SocketChannel) channel;
    } catch (ClassCastException e) {
      logger.warn("unable to cast channel {} to specified type");
    }
    return null;
  }
}
