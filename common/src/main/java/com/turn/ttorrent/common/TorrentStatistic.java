package com.turn.ttorrent.common;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Class store statistic for downloaded, uploaded and left bytes count.
 */
public class TorrentStatistic {

  private final AtomicLong myUploadedBytes;
  private final AtomicLong myDownloadedBytes;
  private final AtomicLong myLeftBytes;

  public TorrentStatistic() {
    myDownloadedBytes = new AtomicLong();
    myUploadedBytes = new AtomicLong();
    myLeftBytes = new AtomicLong();
  }

  public long getUploadedBytes() {
    return myUploadedBytes.get();
  }

  public long getDownloadedBytes() {
    return myDownloadedBytes.get();
  }

  public long getLeftBytes() {
    return myLeftBytes.get();
  }

  public void addUploaded(long delta) {
    myUploadedBytes.addAndGet(delta);
  }

  public void addDownloaded(long delta) {
    myDownloadedBytes.addAndGet(delta);
  }

  public void addLeft(long delta) {
    myLeftBytes.addAndGet(delta);
  }

  public void setLeft(long value) {
    myLeftBytes.set(value);
  }

  public void setUploaded(long value) {
    myUploadedBytes.set(value);
  }

  public void setDownloaded(long value) {
    myDownloadedBytes.set(value);
  }

}