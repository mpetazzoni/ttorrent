package com.turn.ttorrent.common;

import java.util.Collections;
import java.util.List;

public class AnnounceableTorrentImpl implements AnnounceableTorrent {

  private final TorrentStatistic myTorrentStatistic;
  private final String myHexInfoHash;
  private final byte[] myInfoHash;
  private final List<List<String>> myAnnounceUrls;

  public AnnounceableTorrentImpl(TorrentStatistic torrentStatistic,
                                 String hexInfoHash,
                                 byte[] infoHash,
                                 List<List<String>> announceUrls) {
    myTorrentStatistic = torrentStatistic;
    myHexInfoHash = hexInfoHash;
    myInfoHash = infoHash;
    myAnnounceUrls = Collections.unmodifiableList(announceUrls);
  }

  @Override
  public long getUploaded() {
    return myTorrentStatistic.getUploadedBytes();
  }

  @Override
  public long getDownloaded() {
    return myTorrentStatistic.getDownloadedBytes();
  }

  @Override
  public long getLeft() {
    return myTorrentStatistic.getLeftBytes();
  }

  @Override
  public List<List<String>> getAnnounceUrls() {
    return myAnnounceUrls;
  }

  @Override
  public byte[] getInfoHash() {
    return myInfoHash;
  }

  @Override
  public String getHexInfoHash() {
    return myHexInfoHash;
  }
}
