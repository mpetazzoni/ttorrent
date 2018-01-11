package com.turn.ttorrent.tracker;

public interface AddressChecker {

  /**
   * this method must return true if is correct ip and other peers can connect to this peer. If this method return false
   * tracker doesn't register the peer for current torrent
   * @param ip specified address
   */
  boolean isBadAddress(String ip);

}
