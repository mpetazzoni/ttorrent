package com.turn.ttorrent.client;

import com.turn.ttorrent.common.AnnounceableInformation;
import com.turn.ttorrent.common.TorrentHash;

import java.util.List;

class AnnounceableInformationImpl implements AnnounceableInformation {

  private final long uploaded;
  private final long downloaded;
  private final long left;
  private final TorrentHash torrentHash;
  private final List<List<String>> announceUrls;
  private final String announce;

  public AnnounceableInformationImpl(long uploaded,
                                     long downloaded,
                                     long left,
                                     TorrentHash torrentHash,
                                     List<List<String>> announceUrls,
                                     String announce) {
    this.uploaded = uploaded;
    this.downloaded = downloaded;
    this.left = left;
    this.torrentHash = torrentHash;
    this.announceUrls = announceUrls;
    this.announce = announce;
  }

  @Override
  public long getUploaded() {
    return uploaded;
  }

  @Override
  public long getDownloaded() {
    return downloaded;
  }

  @Override
  public long getLeft() {
    return left;
  }

  @Override
  public List<List<String>> getAnnounceList() {
    return announceUrls;
  }

  @Override
  public String getAnnounce() {
    return announce;
  }

  @Override
  public byte[] getInfoHash() {
    return torrentHash.getInfoHash();
  }

  @Override
  public String getHexInfoHash() {
    return torrentHash.getHexInfoHash();
  }
}
