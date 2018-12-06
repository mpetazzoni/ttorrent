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
   * invoked when piece is downloaded but not validated yet
   *
   * @param pieceInformation specified information about piece
   * @param peerInformation  specified information about peer
   */
  void pieceReceived(PieceInformation pieceInformation, PeerInformation peerInformation);

  /**
   * Invoked when download was failed with any exception (e.g. some runtime exception or i/o exception in file operation).
   *
   * @param cause specified exception
   */
  void downloadFailed(Throwable cause);

  /**
   * Invoked when validation of torrent is done.
   * If total pieces count and valid pieces count are equals it means that torrent is fully downloaded.
   * {@link #downloadComplete()} listener will not be invoked in this case
   *
   * @param validpieces count of valid pieces. Must be not greater as #totalpieces
   * @param totalpieces total pieces count in torrent
   */
  void validationComplete(int validpieces, int totalpieces);

}
