package com.turn.ttorrent.network.keyProcessors;

import java.io.IOException;
import java.nio.channels.SelectionKey;

public interface KeyProcessor {

  /**
   * processes the passed key
   *
   * @param key key for processing
   * @throws IOException if an I/O error occurs
   */
  void process(SelectionKey key) throws IOException;

  /**
   * @param key specified key for check acceptance
   * @return true if and only if processor can process this key.
   */
  boolean accept(SelectionKey key);

}
