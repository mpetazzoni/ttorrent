package com.turn.ttorrent.common;

import java.util.List;

public interface AnnounceableInformation extends TorrentHash {

  /**
   * @return number of bytes uploaded by the client for this torrent
   */
  long getUploaded();

  /**
   * @return number of bytes downloaded by the client for this torrent
   */
  long getDownloaded();

  /**
   * @return number of bytes left to download by the client for this torrent
   */
  long getLeft();

  /**
   * @return all tracker for announce
   * @see <a href="http://bittorrent.org/beps/bep_0012.html"></a>
   */
  List<List<String>> getAnnounceList();

  /**
   * @return main announce url for tracker
   */
  String getAnnounce();

}
