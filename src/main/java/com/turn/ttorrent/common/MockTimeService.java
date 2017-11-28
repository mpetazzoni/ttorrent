package com.turn.ttorrent.common;

public class MockTimeService implements TimeService {

  private long time = 0;

  @Override
  public long now() {
    return time;
  }

  public void setTime(long time) {
    this.time = time;
  }
}
