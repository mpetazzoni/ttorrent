package com.turn.ttorrent;

public abstract class WaitFor {
  private long myPollInterval = 100;

  protected WaitFor() {
    this(40 * 1000);
  }

  protected WaitFor(long timeout) {
    long maxWaitMoment = System.currentTimeMillis() + timeout;
    try {
      while(!condition() && System.currentTimeMillis() < maxWaitMoment){
        Thread.sleep(myPollInterval);
      }
    } catch (InterruptedException e) {
      //NOP
    }
  }

  protected abstract boolean condition();
}
