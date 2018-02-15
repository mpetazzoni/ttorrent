package com.turn.ttorrent.common;

import com.turn.ttorrent.client.SharedTorrent;

public class TorrentsPair {

  private final SharedTorrent mySharedTorrent;
  private final AnnounceableFileTorrent myAnnounceableFileTorrent;

  public TorrentsPair(SharedTorrent sharedTorrent, AnnounceableFileTorrent announceableFileTorrent) {
    mySharedTorrent = sharedTorrent;
    myAnnounceableFileTorrent = announceableFileTorrent;
  }

  public SharedTorrent getSharedTorrent() {
    return mySharedTorrent;
  }

  public AnnounceableFileTorrent getAnnounceableFileTorrent() {
    return myAnnounceableFileTorrent;
  }
}
