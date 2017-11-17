package com.turn.ttorrent.client.network;

import com.turn.ttorrent.client.peer.PeerActivityListener;
import com.turn.ttorrent.common.PeersStorageFactory;
import com.turn.ttorrent.common.TorrentsStorageFactory;

public class ChannelListenerFactoryImpl implements ChannelListenerFactory {

  private final PeersStorageFactory peersStorageFactory;
  private final TorrentsStorageFactory torrentsStorageFactory;
  private final PeerActivityListener myPeerActivityListener;


  public ChannelListenerFactoryImpl(PeersStorageFactory peersStorageFactory,
                                    TorrentsStorageFactory torrentsStorageFactory,
                                    PeerActivityListener peerActivityListener) {
    this.peersStorageFactory = peersStorageFactory;
    this.torrentsStorageFactory = torrentsStorageFactory;
    this.myPeerActivityListener = peerActivityListener;
  }

  @Override
  public ChannelListener newChannelListener() {
    return new StateChannelListener(peersStorageFactory, torrentsStorageFactory, myPeerActivityListener);
  }
}
