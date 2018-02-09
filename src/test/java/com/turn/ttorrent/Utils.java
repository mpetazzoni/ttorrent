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

  public static void waitForPeers(final int numPeers, final Collection<TrackedTorrent> torrents) {
    new WaitFor() {
      @Override
      protected boolean condition() {
        for (TrackedTorrent tt : torrents) {
          if (tt.getPeers().size() == numPeers) return true;
        }

        return false;
      }
    };
  }

}
