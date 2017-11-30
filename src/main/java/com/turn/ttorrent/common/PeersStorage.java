package com.turn.ttorrent.common;

import com.turn.ttorrent.client.peer.SharingPeer;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class PeersStorage {

  private volatile Peer self = null;
  private final ReadWriteLock myLock = new ReentrantReadWriteLock();
  //collections contains all connected peers. String key is peer id
  private final ConcurrentHashMap<PeerUID, SharingPeer> connectedSharingPeers;

  private final ConcurrentHashMap<InetSocketAddress, String> addressPeerIdMapping;

  public PeersStorage() {
    this.connectedSharingPeers = new ConcurrentHashMap<PeerUID, SharingPeer>();
    this.addressPeerIdMapping = new ConcurrentHashMap<InetSocketAddress, String>();
  }

  public Peer getSelf() {
    return self;
  }

  public void setSelf(Peer self) {
    this.self = self;
  }

  public SharingPeer putIfAbsent(PeerUID peerId, SharingPeer sharingPeer, boolean needSavePeerIdMapping) {
    Lock writeLock = myLock.writeLock();
    try {
      writeLock.lock();
      SharingPeer result = connectedSharingPeers.putIfAbsent(peerId, sharingPeer);
      if (result == null && needSavePeerIdMapping) {
        addressPeerIdMapping.put(new InetSocketAddress(sharingPeer.getIp(), sharingPeer.getPort()), peerId.getPeerId());
      }
      return result;
    } finally {
      writeLock.unlock();
    }
  }

  public SharingPeer putIfAbsent(PeerUID peerId, SharingPeer sharingPeer) {
    return putIfAbsent(peerId, sharingPeer, false);
  }

  public String getPeerIdByAddress(String ip, int port) {
    Lock readLock = myLock.readLock();
    try {
      readLock.lock();
      return addressPeerIdMapping.get(new InetSocketAddress(ip, port));
    } finally {
      readLock.unlock();
    }
  }

  public SharingPeer removeSharingPeer(PeerUID peerId) {
    Lock writeLock = myLock.writeLock();
    try {
      writeLock.lock();
      SharingPeer result = connectedSharingPeers.remove(peerId);
      if (result != null) {
        addressPeerIdMapping.remove(new InetSocketAddress(result.getIp(), result.getPort()));
      }
      return result;
    } finally {
      writeLock.unlock();
    }
  }

  public SharingPeer getSharingPeer(PeerUID peerId) {
    Lock readLock = myLock.readLock();
    try {
      readLock.lock();
      return connectedSharingPeers.get(peerId);
    } finally {
      readLock.unlock();
    }
  }

  public void removeSharingPeer(SharingPeer peer) {
    Lock writeLock = myLock.writeLock();
    try {
      writeLock.lock();
      boolean removed = connectedSharingPeers.values().remove(peer);
      if (removed) {
        addressPeerIdMapping.remove(new InetSocketAddress(peer.getIp(), peer.getPort()));
      }
    } finally {
      writeLock.unlock();
    }
  }

  public Collection<SharingPeer> getSharingPeers() {
    Lock readLock = myLock.readLock();
    try {
      readLock.lock();
      return new ArrayList<SharingPeer>(connectedSharingPeers.values());
    } finally {
      readLock.unlock();
    }
  }
}
