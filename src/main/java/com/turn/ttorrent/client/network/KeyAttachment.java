package com.turn.ttorrent.client.network;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class KeyAttachment {

  private final static int WRITE_TASK_QUEUE_SIZE = 150;

  private final ConnectionListener connectionListener;
  private final BlockingQueue<WriteTask> writeTasks;

  public KeyAttachment(ConnectionListener connectionListener) {
    this.connectionListener = connectionListener;
    this.writeTasks = new LinkedBlockingQueue<WriteTask>(WRITE_TASK_QUEUE_SIZE);
  }

  public ConnectionListener getConnectionListener() {
    return connectionListener;
  }

  public BlockingQueue<WriteTask> getWriteTasks() {
    return writeTasks;
  }
}
