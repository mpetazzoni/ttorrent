package com.turn.ttorrent.common;

import org.slf4j.Logger;

public class LoggerUtils {

  public static void warnAndDebugDetails(Logger logger, String message, Throwable t) {
    logger.warn(message);
    logger.debug("", t);
  }
}
