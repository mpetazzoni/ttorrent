package com.turn.ttorrent.client.network;

import com.turn.ttorrent.common.TimeService;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class KeyAttachment {

  private final static int WRITE_TASK_QUEUE_SIZE = 150;

  private long lastCommunicationTime;
  private final ConnectionListener connectionListener;
  private final BlockingQueue<WriteTask> writeTasks;
  private final TimeService myTimeService;

  public KeyAttachment(ConnectionListener connectionListener, TimeService timeService) {
    this.myTimeService = timeService;
    this.connectionListener = connectionListener;
    this.writeTasks = new LinkedBlockingQueue<WriteTask>(WRITE_TASK_QUEUE_SIZE);
    this.lastCommunicationTime = timeService.now();
  }

  public ConnectionListener getConnectionListener() {
    return connectionListener;
  }

  public BlockingQueue<WriteTask> getWriteTasks() {
    return writeTasks;
  }

  public long getLastCommunicationTime() {
    return lastCommunicationTime;
  }

  public boolean isTimeoutElapsed(long timeoutMillis) {
    long minTimeForKeepAlive = myTimeService.now() - timeoutMillis;
    return minTimeForKeepAlive > lastCommunicationTime;
  }

  public void communicated() {
    lastCommunicationTime = myTimeService.now();
  }

}
