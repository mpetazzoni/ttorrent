package com.turn.ttorrent.common;

import java.util.concurrent.ConcurrentHashMap;

public class PeersStorage {

  private Peer self = null;
  private final ConcurrentHashMap<String, Peer> connectedPeers;

  public PeersStorage() {
    this.connectedPeers = new ConcurrentHashMap<String, Peer>();
  }

  public Peer getSelf() {
    return self;
  }

  public void setSelf(Peer self) {
    this.self = self;
  }

  public Peer getPeer(String uid) {
    return connectedPeers.get(uid);
  }

  /**
   * try add peer to map. Throw exception if peer with specified id already exist
   *
   * @param uid  key for map
   * @param peer value for map
   * @return true if peer is added to map otherwise false
   */
  public boolean tryAddPeer(String uid, Peer peer) {
    Peer old = connectedPeers.putIfAbsent(uid, peer);
    return old == null;
  }
}
