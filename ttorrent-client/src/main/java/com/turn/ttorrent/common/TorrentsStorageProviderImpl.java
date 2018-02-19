package com.turn.ttorrent.common;

public class TorrentsStorageProviderImpl implements TorrentsStorageProvider {

  private final TorrentsStorage myTorrentsStorage;

  public TorrentsStorageProviderImpl() {
    this.myTorrentsStorage = new TorrentsStorage();
  }

  @Override
  public TorrentsStorage getTorrentsStorage() {
    return this.myTorrentsStorage;
  }
}
