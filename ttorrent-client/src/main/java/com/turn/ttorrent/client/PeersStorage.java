package com.turn.ttorrent.client;

import com.turn.ttorrent.client.peer.SharingPeer;
import com.turn.ttorrent.common.Peer;
import com.turn.ttorrent.common.PeerUID;

import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class PeersStorage {

  private volatile Peer self = null;
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

  public void removeSharingPeer(SharingPeer peer) {
    connectedSharingPeers.values().remove(peer);
  }

  public Collection<SharingPeer> getSharingPeers() {
    return new ArrayList<SharingPeer>(connectedSharingPeers.values());
  }
}
