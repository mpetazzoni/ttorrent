package com.turn.ttorrent.client.network;

import com.turn.ttorrent.common.PeersStorageFactory;
import com.turn.ttorrent.common.TorrentsStorageFactory;

public class ChannelListenerFactoryImpl implements ChannelListenerFactory {

  private final PeersStorageFactory peersStorageFactory;
  private final TorrentsStorageFactory torrentsStorageFactory;

  public ChannelListenerFactoryImpl(PeersStorageFactory peersStorageFactory, TorrentsStorageFactory torrentsStorageFactory) {
    this.peersStorageFactory = peersStorageFactory;
    this.torrentsStorageFactory = torrentsStorageFactory;
  }

  @Override
  public ChannelListener newChannelListener() {
    return new StateChannelListener(peersStorageFactory, torrentsStorageFactory);
  }
}
