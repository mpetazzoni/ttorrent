package com.turn.ttorrent.network;

public interface NewConnectionAllower {

  /**
   * @return true if we can accept new connection or can connect to other peer
   */
  boolean isNewConnectionAllowed();

}
