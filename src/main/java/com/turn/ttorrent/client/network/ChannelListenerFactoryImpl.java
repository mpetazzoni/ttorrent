package com.turn.ttorrent.client.network;

import com.turn.ttorrent.client.peer.PeerActivityListener;
import com.turn.ttorrent.common.PeersStorageProvider;
import com.turn.ttorrent.common.SharingPeerFactoryImpl;
import com.turn.ttorrent.common.SharingPeerRegister;
import com.turn.ttorrent.common.TorrentsStorageProvider;

public class ChannelListenerFactoryImpl implements ChannelListenerFactory {

  private final PeersStorageProvider myPeersStorageProvider;
  private final TorrentsStorageProvider myTorrentsStorageProvider;
  private final SharingPeerRegister mySharingPeerRegister;
  private final SharingPeerFactoryImpl mySharingPeerFactory;


  public ChannelListenerFactoryImpl(PeersStorageProvider peersStorageProvider,
                                    TorrentsStorageProvider torrentsStorageProvider,
                                    SharingPeerRegister sharingPeerRegister,
                                    SharingPeerFactoryImpl sharingPeerFactory) {
    this.myPeersStorageProvider = peersStorageProvider;
    this.myTorrentsStorageProvider = torrentsStorageProvider;
    this.mySharingPeerRegister = sharingPeerRegister;
    this.mySharingPeerFactory = sharingPeerFactory;
  }

  @Override
  public ConnectionListener newChannelListener() {
    return new StateChannelListener(myPeersStorageProvider, myTorrentsStorageProvider, mySharingPeerRegister, mySharingPeerFactory);
  }
}
