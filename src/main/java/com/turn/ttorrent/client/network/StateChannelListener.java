package com.turn.ttorrent.client.network;

import com.turn.ttorrent.client.peer.PeerActivityListener;
import com.turn.ttorrent.common.*;

import java.io.IOException;
import java.nio.channels.SocketChannel;

public class StateChannelListener implements ConnectionListener {

  private DataProcessor next;
  private final PeersStorageProvider myPeersStorageProvider;
  private final TorrentsStorageProvider myTorrentsStorageProvider;
  private final SharingPeerRegister mySharingPeerRegister;
  private final SharingPeerFactory mySharingPeerFactory;

  public StateChannelListener(PeersStorageProvider peersStorageProvider,
                              TorrentsStorageProvider torrentsStorageProvider,
                              SharingPeerRegister sharingPeerRegister,
                              SharingPeerFactory sharingPeerFactory) {
    this.myTorrentsStorageProvider = torrentsStorageProvider;
    this.myPeersStorageProvider = peersStorageProvider;
    this.mySharingPeerRegister = sharingPeerRegister;
    this.mySharingPeerFactory = sharingPeerFactory;
  }

  @Override
  public void onNewDataAvailable(SocketChannel socketChannel) throws IOException {
    this.next = this.next.processAndGetNext(socketChannel);
  }

  @Override
  public void onConnectionEstablished(SocketChannel socketChannel) throws IOException {
    this.next = new HandshakeReceiver(
            myPeersStorageProvider,
            myTorrentsStorageProvider,
            mySharingPeerFactory,
            mySharingPeerRegister,
            socketChannel.socket().getInetAddress().getHostAddress(),
            socketChannel.socket().getPort(),
            false);
  }
}
