package com.turn.ttorrent.tracker;

public interface AddressChecker {

  /**
   * this method must return true if is incorrect ip and other peers can not connect to this peer. If this method return true
   * tracker doesn't register the peer for current torrent
   *
   * @param ip specified address
   */
  boolean isBadAddress(String ip);

}
