package com.turn.ttorrent.network;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class TimeoutStorageImpl implements TimeoutStorage {

  private final AtomicLong timeoutMillis = new AtomicLong();

  @Override
  public void setTimeout(long millis) {
    timeoutMillis.set(millis);
  }

  @Override
  public void setTimeout(int timeout, TimeUnit timeUnit) {
    setTimeout(timeUnit.toMillis(timeout));
  }

  @Override
  public long getTimeoutMillis() {
    return timeoutMillis.get();
  }
}
