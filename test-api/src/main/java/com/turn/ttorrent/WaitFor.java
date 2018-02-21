package com.turn.ttorrent;

public abstract class WaitFor {
  public static final long POLL_INTERVAL = 500;

  private boolean myResult = false;

  protected WaitFor() {
    this(60 * 1000);
  }

  protected WaitFor(long timeout) {
    long maxTime = System.currentTimeMillis() + timeout;
    try {
      while (System.currentTimeMillis() < maxTime && !condition()) {
        Thread.sleep(POLL_INTERVAL);
      }
      if (condition()) {
        myResult = true;
      }
    } catch (InterruptedException e) {
    }
  }

  public boolean isMyResult() {
    return myResult;
  }

  protected abstract boolean condition();
}
