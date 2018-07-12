package com.turn.ttorrent.common;

import java.util.Arrays;

public class ImmutableTorrentHash implements TorrentHash {

  private final byte[] hash;
  private final String hexHash;

  public ImmutableTorrentHash(byte[] hash) {
    this.hash = hash;
    this.hexHash = TorrentUtils.byteArrayToHexString(hash);
  }

  @Override
  public byte[] getInfoHash() {
    return Arrays.copyOf(hash, hash.length);
  }

  @Override
  public String getHexInfoHash() {
    return hexHash;
  }
}
