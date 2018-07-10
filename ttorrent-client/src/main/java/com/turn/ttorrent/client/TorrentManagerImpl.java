package com.turn.ttorrent.client;

import com.turn.ttorrent.common.TorrentHash;

import java.util.Arrays;
import java.util.List;

class TorrentManagerImpl implements TorrentManager {

  private final List<TorrentListener> listeners;
  private final TorrentHash hash;

  public TorrentManagerImpl(List<TorrentListener> listeners, TorrentHash hash) {
    this.listeners = listeners;
    this.hash = hash;
  }

  @Override
  public void addListener(TorrentListener listener) {
    listeners.add(listener);
  }

  @Override
  public boolean removeListener(TorrentListener listener) {
    return listeners.remove(listener);
  }

  @Override
  public byte[] getInfoHash() {
    byte[] hash = this.hash.getInfoHash();
    return Arrays.copyOf(hash, hash.length);
  }

  @Override
  public String getHexInfoHash() {
    return hash.getHexInfoHash();
  }
}
