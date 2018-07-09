package com.turn.ttorrent.client;

public interface TorrentListeners {

  /**
   * add specified listener which will be notified on new events
   *
   * @param listener specified listener
   */
  void addListener(TorrentListener listener);

  /**
   * remove specified listener which was added earlier by {@link TorrentListeners#addListener} method.
   * You can receive events in this listener after execution of the method if notify method was invoked before this method
   *
   * @param listener specified listener
   * @return true if listeners was removed otherwise false (e.g. listener was not found in storage
   */
  boolean removeListener(TorrentListener listener);

  /**
   * Remove the torrent from client.
   * So after invocation of current method all existing connection will be closed, "stop" announce message will be sent to the trackers
   * and {@link TorrentListener#downloadCancelled()} method will be invoked
   */
  void cancel();

}
