package com.turn.ttorrent.client;

import java.util.List;

class TorrentListenersImpl implements TorrentListeners {

  private final List<TorrentListener> listeners;

  public TorrentListenersImpl(List<TorrentListener> listeners) {
    this.listeners = listeners;
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
  public void cancel() {

  }
}
