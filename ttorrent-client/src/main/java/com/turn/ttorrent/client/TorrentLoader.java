package com.turn.ttorrent.client;

import com.turn.ttorrent.common.AnnounceableFileTorrent;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;

public interface TorrentLoader {

  SharedTorrent loadTorrent(AnnounceableFileTorrent announceableFileTorrent) throws IOException, NoSuchAlgorithmException;

}
