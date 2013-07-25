package com.turn.ttorrent;

public abstract class WaitFor {
  private long myPollInterval = 100;

  private boolean myResult = false;

  protected WaitFor() {
    this(60 * 1000);
  }

  protected WaitFor(long timeout) {
    long started = System.currentTimeMillis();
    try {
      while(true) {
        if (condition()) {
          myResult = true;
          return;
        }
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

  protected WaitFor(long timeout, long pollInterval) {
    this(timeout);
    myPollInterval = pollInterval;
  }

  public boolean isMyResult() {
    return myResult;
  }

  protected abstract boolean condition();
}
