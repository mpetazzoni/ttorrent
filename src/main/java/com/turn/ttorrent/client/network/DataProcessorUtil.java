package com.turn.ttorrent.client.network;

import org.slf4j.Logger;

import java.io.IOException;
import java.nio.channels.ByteChannel;

public class DataProcessorUtil {

  public static void closeChannelIfOpen(Logger logger, ByteChannel channel) {
    if (channel.isOpen()) {
      logger.debug("close channel {}", channel);
      try {
        channel.close();
      } catch (IOException e) {
        logger.error("unable to close channel {}", channel);
        logger.debug("", e);
      }
    }
  }

}
