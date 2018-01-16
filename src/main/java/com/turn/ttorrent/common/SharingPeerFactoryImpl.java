package com.turn.ttorrent.common;

import com.turn.ttorrent.client.Client;
import com.turn.ttorrent.client.SharedTorrent;
import com.turn.ttorrent.client.peer.SharingPeer;

import java.nio.ByteBuffer;

public class SharingPeerFactoryImpl implements SharingPeerFactory {

  private final Client myClient;

  public SharingPeerFactoryImpl(Client client) {
    this.myClient = client;
  }

  @Override
  public SharingPeer createSharingPeer(String host, int port, ByteBuffer peerId, SharedTorrent torrent) {
    return new SharingPeer(host, port, peerId, torrent, myClient.getConnectionManager(), myClient);
  }
}
