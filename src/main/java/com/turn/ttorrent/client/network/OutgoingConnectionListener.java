package com.turn.ttorrent.client.network;

import com.turn.ttorrent.common.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ExecutorService;

public class OutgoingConnectionListener implements ConnectionListener {

  private volatile DataProcessor myNext;
  private final PeersStorageProvider myPeersStorageProvider;
  private final TorrentsStorageProvider myTorrentsStorageProvider;
  private final SharingPeerFactory mySharingPeerFactory;
  private final TorrentHash torrentHash;
  private final InetSocketAddress mySendAddress;
  private final ExecutorService myExecutorService;

  public OutgoingConnectionListener(PeersStorageProvider myPeersStorageProvider,
                                    TorrentsStorageProvider myTorrentsStorageProvider,
                                    SharingPeerFactory mySharingPeerFactory,
                                    TorrentHash torrentHash,
                                    InetSocketAddress sendAddress,
                                    ExecutorService myExecutorService) {
    this.myPeersStorageProvider = myPeersStorageProvider;
    this.myTorrentsStorageProvider = myTorrentsStorageProvider;
    this.mySharingPeerFactory = mySharingPeerFactory;
    this.torrentHash = torrentHash;
    this.mySendAddress = sendAddress;
    this.myNext = new ShutdownProcessor();
    this.myExecutorService = myExecutorService;
  }

  @Override
  public void onNewDataAvailable(SocketChannel socketChannel) throws IOException {
    this.myNext = this.myNext.processAndGetNext(socketChannel);
  }

  @Override
  public void onConnectionEstablished(SocketChannel socketChannel) throws IOException {
    HandshakeSender handshakeSender = new HandshakeSender(
            torrentHash,
            myPeersStorageProvider,
            myTorrentsStorageProvider,
            mySendAddress,
            mySharingPeerFactory,
            myExecutorService);
    this.myNext = handshakeSender.processAndGetNext(socketChannel);
  }

  @Override
  public void onError(SocketChannel socketChannel, Throwable ex) throws IOException {
    this.myNext.handleError(socketChannel, ex);
  }
}
