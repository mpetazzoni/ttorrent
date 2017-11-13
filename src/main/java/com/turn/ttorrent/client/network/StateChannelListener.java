package com.turn.ttorrent.client.network;

import com.turn.ttorrent.common.Peer;
import com.turn.ttorrent.common.PeersStorageFactory;
import com.turn.ttorrent.common.TorrentsStorageFactory;

import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.util.UUID;

public class StateChannelListener implements ChannelListener {

  private DataProcessor next;
  private String uid;
  private final PeersStorageFactory peersStorageFactory;
  private final TorrentsStorageFactory torrentsStorageFactory;

  public StateChannelListener(PeersStorageFactory peersStorageFactory, TorrentsStorageFactory torrentsStorageFactory) {
    this.torrentsStorageFactory = torrentsStorageFactory;
    this.peersStorageFactory = peersStorageFactory;
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
    } while (!peersStorageFactory.getPeersStorage().tryAddPeer(uid, peer));
    this.uid = uid;
    this.next = new HandshakeReceiver(uid, peersStorageFactory, torrentsStorageFactory);
  }
}
