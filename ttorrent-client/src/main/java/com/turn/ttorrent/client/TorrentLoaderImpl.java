package com.turn.ttorrent.client;

import com.turn.ttorrent.client.strategy.RequestStrategyImplAnyInteresting;
import com.turn.ttorrent.common.TorrentMetadata;
import com.turn.ttorrent.common.TorrentParser;
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

    final String hexInfoHash = loadedTorrent.getTorrentHash().getHexInfoHash();
    SharedTorrent old = myTorrentsStorage.getTorrent(hexInfoHash);
    if (old != null) {
      return old;
    }

    final File dotTorrentFile = new File(loadedTorrent.getDotTorrentFilePath());
    TorrentMetadata torrentMetadata = new TorrentParser().parseFromFile(dotTorrentFile);

    final SharedTorrent sharedTorrent = new SharedTorrent(torrentMetadata, loadedTorrent.getDownloadDirPath(),
            false, loadedTorrent.isSeeded(), loadedTorrent.isLeeched(),
            new RequestStrategyImplAnyInteresting(),
            loadedTorrent.getTorrentStatistic());

    old = myTorrentsStorage.putIfAbsentActiveTorrent(hexInfoHash, sharedTorrent);
    if (old != null) {
      return old;
    }
    return sharedTorrent;
  }
}
