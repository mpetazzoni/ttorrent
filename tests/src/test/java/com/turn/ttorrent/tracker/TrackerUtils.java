package com.turn.ttorrent.tracker;

import com.turn.ttorrent.common.TorrentGeneralMetadata;
import com.turn.ttorrent.common.TorrentParser;

import java.io.File;
import java.io.IOException;

public class TrackerUtils {

  public static final String TEST_RESOURCES = "src/test/resources";

  public static TrackedTorrent loadTorrent(String name) throws IOException {
    TorrentGeneralMetadata torrentGeneralMetadata = new TorrentParser().parseFromFile(new File(TEST_RESOURCES + "/torrents", name));
    return new TrackedTorrent(torrentGeneralMetadata.getInfoHash());
  }

}
