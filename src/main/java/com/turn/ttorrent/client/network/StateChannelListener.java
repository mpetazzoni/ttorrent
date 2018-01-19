package com.turn.ttorrent.client.network;

import com.turn.ttorrent.common.PeersStorageProvider;
import com.turn.ttorrent.common.SharingPeerFactory;
import com.turn.ttorrent.common.TorrentsStorageProvider;

import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ExecutorService;

public class StateChannelListener implements ConnectionListener {

  private volatile DataProcessor next;
  private final PeersStorageProvider myPeersStorageProvider;
  private final TorrentsStorageProvider myTorrentsStorageProvider;
  private final ExecutorService myExecutorService;
  private final SharingPeerFactory mySharingPeerFactory;

  public StateChannelListener(PeersStorageProvider peersStorageProvider,
                              TorrentsStorageProvider torrentsStorageProvider,
                              ExecutorService executorService,
                              SharingPeerFactory sharingPeerFactory) {
    this.myTorrentsStorageProvider = torrentsStorageProvider;
    this.myPeersStorageProvider = peersStorageProvider;
    this.myExecutorService = executorService;
    this.next = new ShutdownProcessor();
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
            myExecutorService,
            mySharingPeerFactory,
            socketChannel.socket().getInetAddress().getHostAddress(),
            socketChannel.socket().getPort(),
            false);
  }

  @Override
  public void onError(SocketChannel socketChannel, Throwable ex) throws IOException {
    this.next = this.next.handleError(socketChannel, ex);
  }
}
