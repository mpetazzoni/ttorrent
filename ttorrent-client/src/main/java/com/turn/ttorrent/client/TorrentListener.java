package com.turn.ttorrent.client;

public interface TorrentListener {

  void peerConnected(PeerInformation peerInformation);

  void peerDisconnected(PeerInformation peerInformation);

  void pieceDownloaded();

  void downloadComplete();

  void downloadCancelled();
  
}
