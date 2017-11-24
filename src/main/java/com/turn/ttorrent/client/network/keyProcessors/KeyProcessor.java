package com.turn.ttorrent.client.network.keyProcessors;

import java.io.IOException;
import java.nio.channels.SelectionKey;

public interface KeyProcessor {

  void process(SelectionKey key) throws IOException;

  boolean accept(SelectionKey key);

}
