package com.turn.ttorrent.client;

import com.turn.ttorrent.client.strategy.RequestStrategyImplAnyInteresting;
import com.turn.ttorrent.common.TorrentMetadata;
import org.jetbrains.annotations.NotNull;

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

    TorrentMetadata torrentMetadata;
    try {
      torrentMetadata = loadedTorrent.getMetadata();
    } catch (IllegalStateException e) {
      myTorrentsStorage.remove(hexInfoHash);
      throw e;
    }

    final SharedTorrent sharedTorrent = new SharedTorrent(torrentMetadata, loadedTorrent.getPieceStorage(),
            new RequestStrategyImplAnyInteresting(),
            loadedTorrent.getTorrentStatistic(), loadedTorrent.getEventDispatcher());

    old = myTorrentsStorage.putIfAbsentActiveTorrent(hexInfoHash, sharedTorrent);
    if (old != null) {
      return old;
    }
    return sharedTorrent;
  }
}
