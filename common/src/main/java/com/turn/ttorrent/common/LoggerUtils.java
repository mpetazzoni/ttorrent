package com.turn.ttorrent.common;

import org.slf4j.Logger;

public final class LoggerUtils {

  public static void warnAndDebugDetails(Logger logger, String message, Throwable t) {
    logger.warn(message);
    logger.debug("", t);
  }

  public static void warnAndDebugDetails(Logger logger, String message, Object arg, Throwable t) {
    logger.warn(message, arg);
    logger.debug("", t);
  }

  public static void warnWithMessageAndDebugDetails(Logger logger, String message, Object arg, Throwable t) {
    logger.warn(message + ": " + (t.getMessage() != null ? t.getMessage() : t.getClass().getName()), arg);
    logger.debug("", t);
  }

  public static void errorAndDebugDetails(Logger logger, String message, Object arg, Throwable t) {
    logger.error(message, arg);
    logger.debug("", t);
  }

  public static void errorAndDebugDetails(Logger logger, String message, Throwable t) {
    logger.error(message);
    logger.debug("", t);
  }

}
