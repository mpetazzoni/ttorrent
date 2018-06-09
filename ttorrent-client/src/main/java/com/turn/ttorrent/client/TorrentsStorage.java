package com.turn.ttorrent.client;

import com.turn.ttorrent.common.AnnounceableFileTorrent;
import com.turn.ttorrent.common.AnnounceableTorrent;
import com.turn.ttorrent.common.Pair;

import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class TorrentsStorage {

  private final ReadWriteLock myReadWriteLock;
  private final Map<String, SharedTorrent> myActiveTorrents;
  private final Map<String, AnnounceableFileTorrent> myAnnounceableTorrents;

  public TorrentsStorage() {
    myReadWriteLock = new ReentrantReadWriteLock();
    myActiveTorrents = new HashMap<String, SharedTorrent>();
    myAnnounceableTorrents = new HashMap<String, AnnounceableFileTorrent>();
  }

  public boolean hasTorrent(String hash) {
    try {
      myReadWriteLock.readLock().lock();
      return myAnnounceableTorrents.containsKey(hash);
    } finally {
      myReadWriteLock.readLock().unlock();
    }
  }

  public AnnounceableFileTorrent getAnnounceableTorrent(String hash) {
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
      boolean isTorrentFinished = clientState == ClientState.SEEDING || torrent.isSeeder();
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

  public void addAnnounceableTorrent(String hash, AnnounceableFileTorrent torrent) {
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

  public Pair<SharedTorrent, AnnounceableFileTorrent> remove(String hash) {
    final Pair<SharedTorrent, AnnounceableFileTorrent> result;
    try {
      myReadWriteLock.writeLock().lock();
      final SharedTorrent sharedTorrent = myActiveTorrents.remove(hash);
      final AnnounceableFileTorrent announceableFileTorrent = myAnnounceableTorrents.remove(hash);
      result = new Pair<SharedTorrent, AnnounceableFileTorrent>(sharedTorrent, announceableFileTorrent);
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

  public List<AnnounceableTorrent> announceableTorrents() {
    try {
      myReadWriteLock.readLock().lock();
      return new ArrayList<AnnounceableTorrent>(myAnnounceableTorrents.values());
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

  public Pair<SharedTorrent, AnnounceableFileTorrent> removeByTorrentPath(String absolutePath) {
    String torrentHash = null;
    try {
      myReadWriteLock.writeLock().lock();
      for (Map.Entry<String, AnnounceableFileTorrent> e : myAnnounceableTorrents.entrySet()) {
        if (e.getValue().getDotTorrentFilePath().equals(absolutePath)) {
          torrentHash = e.getKey();
          break;
        }
      }
    } finally {
      myReadWriteLock.writeLock().unlock();
    }
    if (torrentHash != null) {
      return remove(torrentHash);
    }
    return new Pair<SharedTorrent, AnnounceableFileTorrent>(null, null);
  }
}
