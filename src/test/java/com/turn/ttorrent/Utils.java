package com.turn.ttorrent;

import com.turn.ttorrent.tracker.TrackedTorrent;
import org.apache.log4j.Level;

import java.util.Collection;

public class Utils {

  private final static String LOG_PROPERTY_KEY = "com.turn.ttorrent.logLevel";

  public static Level getLogLevel() {
    final String levelStr = System.getProperty(LOG_PROPERTY_KEY);
    return Level.toLevel(levelStr, Level.INFO);
  }
}
