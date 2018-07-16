package com.turn.ttorrent.client;

import com.turn.ttorrent.common.AnnounceableInformation;
import com.turn.ttorrent.common.Pair;

import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class TorrentsStorage {

  private final ReadWriteLock myReadWriteLock;
  private final Map<String, SharedTorrent> myActiveTorrents;
  private final Map<String, LoadedTorrent> myAnnounceableTorrents;

  public TorrentsStorage() {
    myReadWriteLock = new ReentrantReadWriteLock();
    myActiveTorrents = new HashMap<String, SharedTorrent>();
    myAnnounceableTorrents = new HashMap<String, LoadedTorrent>();
  }

  public boolean hasTorrent(String hash) {
    try {
      myReadWriteLock.readLock().lock();
      return myAnnounceableTorrents.containsKey(hash);
    } finally {
      myReadWriteLock.readLock().unlock();
    }
  }

  public LoadedTorrent getAnnounceableTorrent(String hash) {
    try {
      myReadWriteLock.readLock().lock();
      return myAnnounceableTorrents.get(hash);
    } finally {
      myReadWriteLock.readLock().unlock();
    }
  }

  public void peerDisconnected(String torrentHash) {
    final SharedTorrent torrent;
    try {
      myReadWriteLock.writeLock().lock();
      torrent = myActiveTorrents.get(torrentHash);
      if (torrent == null) return;

      final ClientState clientState = torrent.getClientState();
      boolean isTorrentFinished = torrent.isFinished();
      if (torrent.getDownloadersCount() == 0 && isTorrentFinished) {
        myActiveTorrents.remove(torrentHash);
      } else {
        return;
      }
    } finally {
      myReadWriteLock.writeLock().unlock();
    }
    torrent.close();
  }

  public SharedTorrent getTorrent(String hash) {
    try {
      myReadWriteLock.readLock().lock();
      return myActiveTorrents.get(hash);
    } finally {
      myReadWriteLock.readLock().unlock();
    }
  }

  public void addAnnounceableTorrent(String hash, LoadedTorrent torrent) {
    try {
      myReadWriteLock.writeLock().lock();
      myAnnounceableTorrents.put(hash, torrent);
    } finally {
      myReadWriteLock.writeLock().unlock();
    }
  }

  public SharedTorrent putIfAbsentActiveTorrent(String hash, SharedTorrent torrent) {
    try {
      myReadWriteLock.writeLock().lock();
      final SharedTorrent old = myActiveTorrents.get(hash);
      if (old != null) return old;

      return myActiveTorrents.put(hash, torrent);
    } finally {
      myReadWriteLock.writeLock().unlock();
    }
  }

  public Pair<SharedTorrent, LoadedTorrent> remove(String hash) {
    final Pair<SharedTorrent, LoadedTorrent> result;
    try {
      myReadWriteLock.writeLock().lock();
      final SharedTorrent sharedTorrent = myActiveTorrents.remove(hash);
      final LoadedTorrent loadedTorrent = myAnnounceableTorrents.remove(hash);
      result = new Pair<SharedTorrent, LoadedTorrent>(sharedTorrent, loadedTorrent);
    } finally {
      myReadWriteLock.writeLock().unlock();
    }
    if (result.first() != null) {
      result.first().close();
    }
    return result;
  }

  public List<SharedTorrent> activeTorrents() {
    try {
      myReadWriteLock.readLock().lock();
      return new ArrayList<SharedTorrent>(myActiveTorrents.values());
    } finally {
      myReadWriteLock.readLock().unlock();
    }
  }

  public List<AnnounceableInformation> announceableTorrents() {
    List<AnnounceableInformation> result = new ArrayList<AnnounceableInformation>();
    try {
      myReadWriteLock.readLock().lock();
      for (LoadedTorrent loadedTorrent : myAnnounceableTorrents.values()) {
        result.add(loadedTorrent.createAnnounceableInformation());
      }
      return result;
    } finally {
      myReadWriteLock.readLock().unlock();
    }
  }

  public void clear() {
    final Collection<SharedTorrent> sharedTorrents;
    try {
      myReadWriteLock.writeLock().lock();
      sharedTorrents = new ArrayList<SharedTorrent>(myActiveTorrents.values());
      myAnnounceableTorrents.clear();
      myActiveTorrents.clear();
    } finally {
      myReadWriteLock.writeLock().unlock();
    }
    for (SharedTorrent sharedTorrent : sharedTorrents) {
      sharedTorrent.close();
    }
  }
}
