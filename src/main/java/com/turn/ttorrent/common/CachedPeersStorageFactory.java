package com.turn.ttorrent.common;

public class CachedPeersStorageFactory implements PeersStorageFactory {

  private final PeersStorage peersStorage;

  public CachedPeersStorageFactory() {
    this.peersStorage = new PeersStorage();
  }

  @Override
  public PeersStorage getPeersStorage() {
    return peersStorage;
  }
}
