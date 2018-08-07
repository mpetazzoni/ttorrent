package com.turn.ttorrent.client;

import com.turn.ttorrent.common.TorrentHash;

import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

class TorrentManagerImpl implements TorrentManager {

  private final List<TorrentListener> listeners;
  private final TorrentHash hash;

  TorrentManagerImpl(List<TorrentListener> listeners, TorrentHash hash) {
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
    return hash.getInfoHash();
  }

  @Override
  public String getHexInfoHash() {
    return hash.getHexInfoHash();
  }

  @Override
  public void awaitDownloadComplete(int timeout, TimeUnit timeUnit) throws InterruptedException, TimeoutException {
    final Semaphore semaphore = new Semaphore(0);
    TorrentListenerWrapper listener = new TorrentListenerWrapper() {
      @Override
      public void downloadComplete() {
        semaphore.release();
      }
    };
    try {
      addListener(listener);
      if (!semaphore.tryAcquire(timeout, timeUnit)) {
        throw new TimeoutException("Unable to download torrent in specified timeout");
      }
    } finally {
      removeListener(listener);
    }
  }
}
