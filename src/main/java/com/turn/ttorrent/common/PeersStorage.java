package com.turn.ttorrent.common;

import com.turn.ttorrent.client.peer.SharingPeer;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

public class PeersStorage {

  private Peer self = null;
  //collections contains all connected peers. String key is peer id
  private final ConcurrentHashMap<PeerUID, SharingPeer> connectedSharingPeers;

  public PeersStorage() {
    this.connectedSharingPeers = new ConcurrentHashMap<PeerUID, SharingPeer>();
  }

  public Peer getSelf() {
    return self;
  }

  public void setSelf(Peer self) {
    this.self = self;
  }

  public SharingPeer putIfAbsent(PeerUID peerId, SharingPeer sharingPeer) {
    return connectedSharingPeers.putIfAbsent(peerId, sharingPeer);
  }

  public SharingPeer removeSharingPeer(PeerUID peerId) {
    return connectedSharingPeers.remove(peerId);
  }

  public SharingPeer getSharingPeer(PeerUID peerId) {
    return connectedSharingPeers.get(peerId);
  }

  public Collection<SharingPeer> getSharingPeers() {
    return connectedSharingPeers.values();
  }
}
