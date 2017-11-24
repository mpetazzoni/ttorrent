package com.turn.ttorrent.client.network;

public interface WriteListener {

  void onWriteFailed();

  void onWriteDone();

}
