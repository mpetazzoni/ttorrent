package com.turn.ttorrent.common;

public class CachedTorrentsStorageFactory implements TorrentsStorageFactory {

  private final TorrentsStorage torrentsStorage;

  public CachedTorrentsStorageFactory() {
    this.torrentsStorage = new TorrentsStorage();
  }

  @Override
  public TorrentsStorage getTorrentsStorage() {
    return this.torrentsStorage;
  }
}
