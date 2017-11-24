package com.turn.ttorrent.client.network;

import com.turn.ttorrent.common.PeerUID;

import java.nio.ByteBuffer;

public class WriteTask {

  private final PeerUID peerUID;
  private final ByteBuffer byteBuffer;
  private final WriteListener listener;

  public WriteTask(PeerUID peerUID, ByteBuffer byteBuffer, WriteListener listener) {
    this.peerUID = peerUID;
    this.byteBuffer = byteBuffer;
    this.listener = listener;
  }
}
