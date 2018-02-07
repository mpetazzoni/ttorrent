package com.turn.ttorrent.client;

import com.turn.ttorrent.client.network.ChannelListenerFactory;
import com.turn.ttorrent.common.PeersStorage;
import com.turn.ttorrent.common.SharingPeerFactory;
import com.turn.ttorrent.common.TorrentsStorage;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public interface Context extends SharingPeerFactory, ChannelListenerFactory{

  /**
   * @return single instance of peers storage
   */
  PeersStorage getPeersStorage();

  /**
   * @return single instance of torrents storage
   */
  TorrentsStorage getTorrentsStorage();

  /**
   * @return executor for handling incoming messages
   */
  ExecutorService getExecutor();

}
