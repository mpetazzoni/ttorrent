package com.turn.ttorrent.common;

public interface AnnounceableFileTorrent extends AnnounceableTorrent, TorrentStatisticProvider, FileTorrent {

  boolean isSeeded();

}
