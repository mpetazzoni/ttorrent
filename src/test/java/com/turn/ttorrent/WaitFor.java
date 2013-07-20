package com.turn.ttorrent;

public abstract class WaitFor {
  private long myPollInterval = 100;

    private boolean myIsConditionMet = false;

    public static final long DEFAULT_POLL_INTERVAL = 100;
    public static final long DEFAULT_TIMEOUT = 15*1000;

  protected WaitFor() {
    this(DEFAULT_TIMEOUT,DEFAULT_POLL_INTERVAL);
  }

  protected WaitFor(long timeout, long pollInterval) {
    myPollInterval = pollInterval;
    long started = System.currentTimeMillis();
    try {
      while(true) {
        if (myIsConditionMet=condition()) return;
        if (System.currentTimeMillis() - started < timeout) {
          Thread.sleep(myPollInterval);
        } else {
          break;
        }
      }

    } catch (InterruptedException e) {
      //NOP
    }
  }

  protected WaitFor(long timeout) {
      this(timeout, DEFAULT_POLL_INTERVAL);
  }

    public boolean isConditionMet() {
        return myIsConditionMet;
    }

    protected abstract boolean condition();
}
