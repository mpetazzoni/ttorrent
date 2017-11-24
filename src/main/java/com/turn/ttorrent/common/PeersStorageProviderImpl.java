package com.turn.ttorrent.common;

public class PeersStorageProviderImpl implements PeersStorageProvider {

  private final PeersStorage myPeersStorage;

  public PeersStorageProviderImpl() {
    this.myPeersStorage = new PeersStorage();
  }

  @Override
  public PeersStorage getPeersStorage() {
    return myPeersStorage;
  }
}
