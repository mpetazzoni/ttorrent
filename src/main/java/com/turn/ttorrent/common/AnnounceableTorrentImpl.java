package com.turn.ttorrent.common;

import java.util.Collections;
import java.util.List;

public class AnnounceableTorrentImpl implements AnnounceableFileTorrent {

  private final TorrentStatistic myTorrentStatistic;
  private final String myHexInfoHash;
  private final byte[] myInfoHash;
  private final List<List<String>> myAnnounceUrls;
  private final String myAnnounce;
  private final String myRealFilePath;
  private final String myDotTorrentFilePath;

  public AnnounceableTorrentImpl(TorrentStatistic torrentStatistic,
                                 String hexInfoHash,
                                 byte[] infoHash,
                                 List<List<String>> announceUrls,
                                 String announce,
                                 String realFilePath,
                                 String dotTorrentFilePath) {
    myTorrentStatistic = torrentStatistic;
    myHexInfoHash = hexInfoHash;
    myInfoHash = infoHash;
    myAnnounceUrls = Collections.unmodifiableList(announceUrls);
    myAnnounce = announce;
    myDotTorrentFilePath = dotTorrentFilePath;
    myRealFilePath = realFilePath;
  }

  @Override
  public String getRealFilePath() {
    return myRealFilePath;
  }

  @Override
  public String getDotTorrentFilePath() {
    return myDotTorrentFilePath;
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
  public List<List<String>> getAnnounceList() {
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

  @Override
  public String getAnnounce() {
    return myAnnounce;
  }
}
