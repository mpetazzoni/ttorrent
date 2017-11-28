package com.turn.ttorrent.client.network.keyProcessors;

import java.nio.channels.SelectionKey;

public interface CleanupProcessor {

  /**
   * invoked when the cleanup procedure is running. Processor can cancel key and/or close channel if necessary
   *
   * @param key specified key
   */
  void processCleanup(SelectionKey key);

  /**
   * invoked when writing or reading is available for channel associated with this key
   *
   * @param key specified key
   */
  void processSelected(SelectionKey key);

}
