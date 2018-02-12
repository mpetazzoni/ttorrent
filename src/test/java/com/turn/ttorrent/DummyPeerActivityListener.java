package com.turn.ttorrent;

import com.turn.ttorrent.client.Piece;
import com.turn.ttorrent.client.peer.PeerActivityListener;
import com.turn.ttorrent.client.peer.SharingPeer;

import java.io.IOException;
import java.util.BitSet;

public class DummyPeerActivityListener implements PeerActivityListener {


  @Override
  public void handlePeerChoked(SharingPeer peer) {

  }

  @Override
  public void handlePeerReady(SharingPeer peer) {

  }

  @Override
  public void afterPeerRemoved(SharingPeer peer) {

  }

  @Override
  public void handlePieceAvailability(SharingPeer peer, Piece piece) {

  }

  @Override
  public void handleBitfieldAvailability(SharingPeer peer, BitSet availablePieces) {

  }

  @Override
  public void handlePieceSent(SharingPeer peer, Piece piece) {

  }

  @Override
  public void handlePieceCompleted(SharingPeer peer, Piece piece) throws IOException {

  }

  @Override
  public void handlePeerDisconnected(SharingPeer peer) {

  }

  @Override
  public void handleIOException(SharingPeer peer, IOException ioe) {

  }

  @Override
  public void handleNewPeerConnected(SharingPeer peer) {

  }
}
