package com.turn.ttorrent.network;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.channels.SocketChannel;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class ReadWriteAttachment implements ReadAttachment, WriteAttachment, TimeoutAttachment {

  private final static int WRITE_TASK_QUEUE_SIZE = 150;

  private long lastCommunicationTime;
  private final ConnectionListener connectionListener;
  private final long myTimeoutMillis;
  private final BlockingQueue<WriteTask> writeTasks;

  public ReadWriteAttachment(ConnectionListener connectionListener, long lastCommunicationTime, long timeoutMillis) {
    this.connectionListener = connectionListener;
    this.writeTasks = new LinkedBlockingQueue<WriteTask>(WRITE_TASK_QUEUE_SIZE);
    this.lastCommunicationTime = lastCommunicationTime;
    this.myTimeoutMillis = timeoutMillis;
  }

  @Override
  public ConnectionListener getConnectionListener() {
    return connectionListener;
  }

  @Override
  public BlockingQueue<WriteTask> getWriteTasks() {
    return writeTasks;
  }

  @Override
  public boolean isTimeoutElapsed(long currentTimeMillis) {
    long minTimeForKeepAlive = currentTimeMillis - myTimeoutMillis;
    return minTimeForKeepAlive > lastCommunicationTime;
  }

  @Override
  public void communicatedNow(long currentTimeMillis) {
    lastCommunicationTime = currentTimeMillis;
  }

  @Override
  public void onTimeoutElapsed(SocketChannel channel) throws IOException {
    connectionListener.onError(channel, new SocketTimeoutException());
  }
}
