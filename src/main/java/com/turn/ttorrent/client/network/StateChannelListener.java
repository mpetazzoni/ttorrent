package com.turn.ttorrent.client.network;

import com.turn.ttorrent.client.peer.PeerActivityListener;
import com.turn.ttorrent.common.Peer;
import com.turn.ttorrent.common.PeersStorageProvider;
import com.turn.ttorrent.common.TorrentsStorageProvider;
import sun.reflect.generics.reflectiveObjects.NotImplementedException;

import java.io.IOException;
import java.nio.channels.SocketChannel;

public class StateChannelListener implements ChannelListener {

  private DataProcessor next;
  private final PeersStorageProvider myPeersStorageProvider;
  private final TorrentsStorageProvider myTorrentsStorageProvider;
  private final PeerActivityListener myPeerActivityListener;

  public StateChannelListener(PeersStorageProvider peersStorageProvider,
                              TorrentsStorageProvider torrentsStorageProvider,
                              PeerActivityListener myPeerActivityListener) {
    this.myTorrentsStorageProvider = torrentsStorageProvider;
    this.myPeersStorageProvider = peersStorageProvider;
    this.myPeerActivityListener = myPeerActivityListener;
  }

  @Override
  public void onNewDataAvailable(SocketChannel socketChannel) throws IOException {
    this.next = this.next.processAndGetNext(socketChannel);
  }

  @Override
  public void onConnectionAccept(SocketChannel socketChannel) throws IOException {
    this.next = new HandshakeReceiver(
            myPeersStorageProvider,
            myTorrentsStorageProvider,
            myPeerActivityListener,
            socketChannel.socket().getInetAddress().getHostAddress(),
            socketChannel.socket().getPort());
  }

  @Override
  public void onConnected(SocketChannel socketChannel, Peer peer) {
    // TODO: 11/15/17 impl
    throw new NotImplementedException();
//    registerNewPeer(peer);
//    ConnectionUtils.sendHandshake(socketChannel, peer.getInfoHash(), peersStorageFactory.getPeersStorage().getSelf().getPeerIdArray());
//    this.next = new HandshakeReceiver(uid, peersStorageFactory, torrentsStorageFactory, false);
  }
}
