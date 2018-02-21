package com.turn.ttorrent.network.keyProcessors;

import java.nio.channels.SelectionKey;

public interface CleanupProcessor {

  /**
   * invoked when the cleanup procedure is running. Processor can cancel key and/or close channel if necessary
   *
   * @param key specified key
   */
  void processCleanup(SelectionKey key);

  /**
   * invoked when get any activity for channel associated with this key
   *
   * @param key specified key
   */
  void processSelected(SelectionKey key);

}
