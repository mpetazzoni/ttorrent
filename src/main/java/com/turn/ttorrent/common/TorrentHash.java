package com.turn.ttorrent.common;

public interface TorrentHash {
  /**
   * Return the hash of the B-encoded meta-info structure of a torrent.
   */
  byte[] getInfoHash();

  /**
   * Get torrent's info hash (as an hexadecimal-coded string).
   */
  String getHexInfoHash();
}
