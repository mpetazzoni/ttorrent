package com.turn.ttorrent.client.network;

import com.turn.ttorrent.client.peer.PeerActivityListener;
import com.turn.ttorrent.common.PeersStorageProvider;
import com.turn.ttorrent.common.TorrentsStorageProvider;

public class ChannelListenerFactoryImpl implements ChannelListenerFactory {

  private final PeersStorageProvider myPeersStorageProvider;
  private final TorrentsStorageProvider myTorrentsStorageProvider;
  private final PeerActivityListener myPeerActivityListener;


  public ChannelListenerFactoryImpl(PeersStorageProvider peersStorageProvider,
                                    TorrentsStorageProvider torrentsStorageProvider,
                                    PeerActivityListener peerActivityListener) {
    this.myPeersStorageProvider = peersStorageProvider;
    this.myTorrentsStorageProvider = torrentsStorageProvider;
    this.myPeerActivityListener = peerActivityListener;
  }

  @Override
  public ConnectionListener newChannelListener() {
    return new StateChannelListener(myPeersStorageProvider, myTorrentsStorageProvider, myPeerActivityListener);
  }
}
