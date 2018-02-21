package com.turn.ttorrent.client.network;

import com.turn.ttorrent.client.PeersStorage;
import com.turn.ttorrent.network.NewConnectionAllower;

import java.util.concurrent.atomic.AtomicInteger;

import static com.turn.ttorrent.Constants.DEFAULT_MAX_CONNECTION_COUNT;

/**
 * this implementation allows fixed count of open connection simultaneously
 */
public class CountLimitConnectionAllower implements NewConnectionAllower {

  private final PeersStorage myPeersStorage;

  private final AtomicInteger myMaxConnectionCount = new AtomicInteger();

  public CountLimitConnectionAllower(PeersStorage peersStorage) {
    this.myPeersStorage = peersStorage;
    myMaxConnectionCount.set(DEFAULT_MAX_CONNECTION_COUNT);

  }

  public void setMyMaxConnectionCount(int newMaxCount) {
    myMaxConnectionCount.set(newMaxCount);
  }

  @Override
  public boolean isNewConnectionAllowed() {
    return myPeersStorage.getSharingPeers().size() < myMaxConnectionCount.get();
  }
}
