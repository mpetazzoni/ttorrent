package com.turn.ttorrent.common;

public interface TorrentSingleFileMetadata extends TorrentGeneralMetadata {

  /**
   * @return name of file in torrent
   */
  String getFileName();

  /**
   * @return length of file in bytes, which is stored in torrent
   */
  long getLengthBytes();

  /**
   * @return 32-character hexadecimal string corresponding to the MD5 sum of the file
   */
  Optional<String> getMD5Sum();

}
