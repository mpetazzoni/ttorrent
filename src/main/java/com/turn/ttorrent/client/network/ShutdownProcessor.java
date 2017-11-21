package com.turn.ttorrent.client.network;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.ByteChannel;

public class ShutdownProcessor implements DataProcessor {

  private static final Logger logger = LoggerFactory.getLogger(ShutdownProcessor.class);

  @Override
  public DataProcessor processAndGetNext(ByteChannel socketChannel) throws IOException {
    if (socketChannel.isOpen()) {
      try {
        socketChannel.close();
      } catch (IOException e) {
        logger.error("unable to close channel {}", socketChannel);
        logger.debug("", e);
      }
    }
    return this;
  }
}
