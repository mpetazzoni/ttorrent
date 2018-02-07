package com.turn.ttorrent.common;

public interface AnnounceableFileTorrent extends AnnounceableTorrent {

  String getRealFilePath();

  String getDotTorrentFilePath();

}
