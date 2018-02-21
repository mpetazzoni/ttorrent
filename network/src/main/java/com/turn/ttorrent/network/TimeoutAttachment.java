package com.turn.ttorrent.network;

import java.io.IOException;
import java.nio.channels.SocketChannel;

public interface TimeoutAttachment {

  /**
   * @param currentTimeMillis current time for timeout calculation
   * @return true, if and only if timeout was elapsed
   */
  boolean isTimeoutElapsed(long currentTimeMillis);

  /**
   * set last communication time to current time
   *
   * @param currentTimeMillis current time in milliseconds
   */
  void communicatedNow(long currentTimeMillis);

  /**
   * must be invoked if timeout was elapsed
   *
   * @param channel specified channel for key associated with this attachment
   * @throws IOException if an I/O error occurs
   */
  void onTimeoutElapsed(SocketChannel channel) throws IOException;

}
