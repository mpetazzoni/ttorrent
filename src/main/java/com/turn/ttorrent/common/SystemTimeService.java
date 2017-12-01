package com.turn.ttorrent.common;

public class SystemTimeService implements TimeService {

  @Override
  public long now() {
    return System.currentTimeMillis();
  }
}
