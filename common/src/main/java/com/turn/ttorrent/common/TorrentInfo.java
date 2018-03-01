package com.turn.ttorrent.common;

/**
 * @author Sergey.Pak
 * Date: 8/9/13
 * Time: 6:00 PM
 */
public interface TorrentInfo extends TorrentHash {

  /*
  * Number of bytes uploaded by the client for this torrent
  * */
  long getUploaded();

  /*
  * Number of bytes downloaded by the client for this torrent
  * */
  long getDownloaded();

  /*
  * Number of bytes left to download by the client for this torrent
  * */
  long getLeft();

  int getPieceCount();

  long getPieceSize(int pieceIdx);
}
