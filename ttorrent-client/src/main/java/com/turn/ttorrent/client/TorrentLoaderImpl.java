package com.turn.ttorrent.client;

import com.turn.ttorrent.common.AnnounceableFileTorrent;

import java.io.File;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;

public class TorrentLoaderImpl implements TorrentLoader {

  private final TorrentsStorage myTorrentsStorage;

  public TorrentLoaderImpl(TorrentsStorage torrentsStorage) {
    myTorrentsStorage = torrentsStorage;
  }

  @Override
  public SharedTorrent loadTorrent(AnnounceableFileTorrent announceableFileTorrent) throws IOException, NoSuchAlgorithmException {

    final String hexInfoHash = announceableFileTorrent.getHexInfoHash();
    SharedTorrent old = myTorrentsStorage.getTorrent(hexInfoHash);
    if (old != null) {
      return old;
    }

    final File dotTorrentFile = new File(announceableFileTorrent.getDotTorrentFilePath());
    final File downloadDir = new File(announceableFileTorrent.getDownloadDirPath());

    final SharedTorrent sharedTorrent = SharedTorrent.fromFile(dotTorrentFile, downloadDir, false,
            announceableFileTorrent.isSeeded());

    old = myTorrentsStorage.putIfAbsentActiveTorrent(hexInfoHash, sharedTorrent);
    if (old != null) {
      return old;
    }
    return sharedTorrent;
  }
}
