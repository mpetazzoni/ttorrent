package com.turn.ttorrent.client;

public interface TorrentListener {

  /**
   * Invoked when connection with peer is established
   *
   * @param peerInformation specified information about peer
   */
  void peerConnected(PeerInformation peerInformation);

  /**
   * Invoked when connection with peer is closed.
   *
   * @param peerInformation specified information about peer
   */
  void peerDisconnected(PeerInformation peerInformation);

  /**
   * Invoked when piece is downloaded and validated
   *
   * @param pieceInformation specified information about piece
   * @param peerInformation  specified information about peer
   */
  void pieceDownloaded(PieceInformation pieceInformation, PeerInformation peerInformation);

  /**
   * Invoked when downloading is fully downloaded (last piece is received and validated)
   */
  void downloadComplete();


  /**
   * Invoked when download was failed with any exception (e.g. some runtime exception or i/o exception in file operation).
   *
   * @param cause specified exception
   */
  void downloadFailed(Throwable cause);

}
