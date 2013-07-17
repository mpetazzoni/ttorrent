package com.turn.ttorrent;

public abstract class WaitFor {
  private long myPollInterval = 100;

  protected WaitFor() {
    this(40 * 1000);
  }

  protected WaitFor(long timeout) {
    long started = System.currentTimeMillis();
    try {
      while(true) {
        if (condition()) return;
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

  protected abstract boolean condition();
}
