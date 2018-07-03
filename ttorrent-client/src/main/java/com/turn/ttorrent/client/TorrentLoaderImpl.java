package com.turn.ttorrent.client;

import com.turn.ttorrent.common.LoadedTorrent;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;

public class TorrentLoaderImpl implements TorrentLoader {

  @NotNull
  private final TorrentsStorage myTorrentsStorage;

  public TorrentLoaderImpl(@NotNull TorrentsStorage torrentsStorage) {
    myTorrentsStorage = torrentsStorage;
  }

  @Override
  @NotNull
  public SharedTorrent loadTorrent(@NotNull LoadedTorrent loadedTorrent) throws IOException {

    final String hexInfoHash = loadedTorrent.getHexInfoHash();
    SharedTorrent old = myTorrentsStorage.getTorrent(hexInfoHash);
    if (old != null) {
      return old;
    }

    final File dotTorrentFile = new File(loadedTorrent.getDotTorrentFilePath());
    final File downloadDir = new File(loadedTorrent.getDownloadDirPath());

    final SharedTorrent sharedTorrent = SharedTorrent.fromFile(dotTorrentFile, downloadDir, false,
            loadedTorrent.isSeeded(), loadedTorrent.isLeeched(), loadedTorrent.getTorrentStatistic());

    old = myTorrentsStorage.putIfAbsentActiveTorrent(hexInfoHash, sharedTorrent);
    if (old != null) {
      return old;
    }
    return sharedTorrent;
  }
}
