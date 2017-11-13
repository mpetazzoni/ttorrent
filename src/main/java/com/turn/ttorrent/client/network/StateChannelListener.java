package com.turn.ttorrent.client.network;

import com.turn.ttorrent.client.Client;
import com.turn.ttorrent.common.Peer;

import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.util.UUID;

public class StateChannelListener implements ChannelListener {

  private final Client client;

  private DataProcessor next;
  private String uid;

  public StateChannelListener(Client client) {
    this.client = client;
  }

  @Override
  public void onNewDataAvailable(SocketChannel socketChannel) throws IOException {
    this.next = this.next.processAndGetNext(socketChannel);
  }

  @Override
  public void onConnectionAccept(SocketChannel socketChannel) throws IOException {
    Peer peer = new Peer(socketChannel.socket().getInetAddress().getHostAddress(),
            socketChannel.socket().getPort(), null);
    String uid;
    do {
      uid = UUID.randomUUID().toString();
    } while (!client.peersStorage.tryAddPeer(uid, peer));
    this.uid = uid;
    this.next = new HandshakeReceiver(uid, client);
  }
}
