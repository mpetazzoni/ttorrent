package com.turn.ttorrent.client.network;

import com.turn.ttorrent.client.peer.PeerActivityListener;
import com.turn.ttorrent.common.PeersStorageProvider;
import com.turn.ttorrent.common.TorrentHash;
import com.turn.ttorrent.common.TorrentsStorageProvider;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;

public class OutgoingConnectionListener implements ConnectionListener {

  private DataProcessor myNext;
  private final PeersStorageProvider myPeersStorageProvider;
  private final TorrentsStorageProvider myTorrentsStorageProvider;
  private final PeerActivityListener myPeerActivityListener;
  private final TorrentHash torrentHash;
  private final InetSocketAddress mySendAddress;

  public OutgoingConnectionListener(PeersStorageProvider myPeersStorageProvider,
                                    TorrentsStorageProvider myTorrentsStorageProvider,
                                    PeerActivityListener myPeerActivityListener,
                                    TorrentHash torrentHash,
                                    InetSocketAddress sendAddress) {
    this.myPeersStorageProvider = myPeersStorageProvider;
    this.myTorrentsStorageProvider = myTorrentsStorageProvider;
    this.myPeerActivityListener = myPeerActivityListener;
    this.torrentHash = torrentHash;
    this.mySendAddress = sendAddress;
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
            myPeerActivityListener,
            mySendAddress);
    this.myNext = handshakeSender.processAndGetNext(socketChannel);
  }
}
