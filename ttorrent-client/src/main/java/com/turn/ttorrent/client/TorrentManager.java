package com.turn.ttorrent.client;

import com.turn.ttorrent.common.TorrentHash;

public interface TorrentManager extends TorrentHash {

  /**
   * add specified listener which will be notified on new events
   *
   * @param listener specified listener
   */
  void addListener(TorrentListener listener);

  /**
   * remove specified listener which was added earlier by {@link TorrentManager#addListener} method.
   * You can receive events in this listener after execution of the method if notify method was invoked before this method
   *
   * @param listener specified listener
   * @return true if listeners was removed otherwise false (e.g. listener was not found)
   */
  boolean removeListener(TorrentListener listener);

}
