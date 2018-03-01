package com.turn.ttorrent.client;

public interface DownloadProgressListener {

  /**
   * Invoked when new piece was loaded and successfully validated
   *
   * @param pieceIndex index of loaded piece
   * @param pieceSize  size of loaded piece. All pieces, except the last one, are expected to have the same size.
   */
  void pieceLoaded(int pieceIndex, int pieceSize);

  class NopeListener implements DownloadProgressListener {
    @Override
    public void pieceLoaded(int pieceIndex, int pieceSize) {
    }
  }
}
