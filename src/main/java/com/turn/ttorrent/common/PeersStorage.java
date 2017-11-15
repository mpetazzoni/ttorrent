package com.turn.ttorrent.common;

import com.turn.ttorrent.client.peer.SharingPeer;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

public class PeersStorage {

  private Peer self = null;
  private final ConcurrentHashMap<String, Peer> connectedPeers;//this collections contains connected peers which don't send handshake
  private final ConcurrentHashMap<Peer, SharingPeer> connectedSharingPeers;//this collection contains connected peers with correct handshake

  public PeersStorage() {
    this.connectedPeers = new ConcurrentHashMap<String, Peer>();
    this.connectedSharingPeers = new ConcurrentHashMap<Peer, SharingPeer>();
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

  public Peer removePeer(String uid) {
    return connectedPeers.remove(uid);
  }

  public void addSharingPeer(Peer peer, SharingPeer sharingPeer) {
    if (peer.getHexInfoHash() == null) {
      throw new NullPointerException("can not add sharing peer with peer without torrent");// TODO: 11/14/17 correct handle this case
    }
    connectedSharingPeers.put(peer, sharingPeer);
  }

  public SharingPeer tryAddSharingPeer(Peer peer, SharingPeer sharingPeer) {
    return connectedSharingPeers.putIfAbsent(peer, sharingPeer);
  }

  public SharingPeer removeSharingPeer(Peer peer) {
    return connectedSharingPeers.remove(peer);
  }

  public SharingPeer getSharingPeer(Peer peer) {
    return connectedSharingPeers.get(peer);
  }

  public Collection<SharingPeer> getSharingPeers() {
    return connectedSharingPeers.values();
  }
}
