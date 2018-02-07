package com.turn.ttorrent.common;

import com.turn.ttorrent.client.SharedTorrent;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class TorrentsStorage {

  private final ReadWriteLock myReadWriteLock;
  private final ConcurrentMap<String, SharedTorrent> myActiveTorrents;
  private final ConcurrentMap<String, AnnounceableTorrent> myAnnounceableTorrents;
  private final ConcurrentMap<String, String> myPathToTorrentMetadataFile;
  private final ConcurrentMap<String, String> myPathToReallyFile;

  public TorrentsStorage() {
    myReadWriteLock = new ReentrantReadWriteLock();
    myActiveTorrents = new ConcurrentHashMap<String, SharedTorrent>();
    myAnnounceableTorrents = new ConcurrentHashMap<String, AnnounceableTorrent>();
    myPathToTorrentMetadataFile = new ConcurrentHashMap<String, String>();
    myPathToReallyFile = new ConcurrentHashMap<String, String>();
  }

  public boolean hasTorrent(String hash) {
    return myAnnounceableTorrents.containsKey(hash);
  }

  public SharedTorrent getTorrent(String hash) {
    return myActiveTorrents.get(hash);
  }

  public void addAnnounceableTorrent(String hash, AnnounceableTorrent torrent, String torrentFilePath, String reallyFilePath) {
    try {
      myReadWriteLock.writeLock().lock();
      myAnnounceableTorrents.put(hash, torrent);
      myPathToReallyFile.put(hash, reallyFilePath);
      myPathToTorrentMetadataFile.put(hash, torrentFilePath);
    } finally {
      myReadWriteLock.writeLock().unlock();
    }
  }

  public SharedTorrent putIfAbsentActiveTorrent(String hash, SharedTorrent torrent) {
    return myActiveTorrents.putIfAbsent(hash, torrent);
  }

  public SharedTorrent remove(String hash) {
    try {
      myReadWriteLock.writeLock().lock();
      myAnnounceableTorrents.remove(hash);
      myPathToReallyFile.remove(hash);
      myPathToTorrentMetadataFile.remove(hash);
      return myActiveTorrents.remove(hash);
    } finally {
      myReadWriteLock.writeLock().unlock();
    }
  }

  public Collection<SharedTorrent> activeTorrents() {
    return myActiveTorrents.values();
  }

  public Collection<AnnounceableTorrent> announceableTorrents() {
    return myAnnounceableTorrents.values();
  }

}
