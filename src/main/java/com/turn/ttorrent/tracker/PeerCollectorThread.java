package com.turn.ttorrent.tracker;

import java.util.concurrent.ConcurrentMap;

/**
 * The unfresh peer collector thread.
 *
 * <p>
 * Every PEER_COLLECTION_FREQUENCY_SECONDS, this thread will collect
 * unfresh peers from all announced torrents.
 * </p>
 */
public class PeerCollectorThread extends Thread {

  public static final int COLLECTION_FREQUENCY=10;
  private ConcurrentMap<String, TrackedTorrent> myTorrents;
  private volatile int myTorrentExpireTimeoutSec = 60;

  public PeerCollectorThread(final ConcurrentMap<String, TrackedTorrent> torrents) {
    myTorrents = torrents;
  }

  public void setTorrentExpireTimeoutSec(int torrentExpireTimeoutSec) {
    myTorrentExpireTimeoutSec = torrentExpireTimeoutSec;
  }

  @Override
  public void run() {
    while (!isInterrupted()) {
      for (TrackedTorrent torrent : myTorrents.values()) {
        torrent.collectUnfreshPeers(myTorrentExpireTimeoutSec);
        if (torrent.getPeers().size() == 0){
          myTorrents.remove(torrent.getHexInfoHash());
        }
      }
      try {
        Thread.sleep(COLLECTION_FREQUENCY * 1000);
      } catch (InterruptedException ie) {
        break;
      }
    }
  }
}
