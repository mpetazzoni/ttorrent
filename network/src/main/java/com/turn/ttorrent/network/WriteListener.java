package com.turn.ttorrent.network;

public interface WriteListener {

  /**
   * invoked if write is failed by any reason
   *
   * @param message error description
   * @param e       exception if exist. Otherwise null
   */
  void onWriteFailed(String message, Throwable e);

  /**
   * invoked if write done correctly
   */
  void onWriteDone();

}
