package com.turn.ttorrent.tracker;

import com.turn.ttorrent.common.Torrent;

import java.io.File;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;

public class TrackerUtils {

  public static final String TEST_RESOURCES = "src/test/resources";

  public static TrackedTorrent loadTorrent(String name) throws IOException, NoSuchAlgorithmException {
    return new TrackedTorrent(Torrent.load(new File(TEST_RESOURCES + "/torrents", name)));
  }

}
