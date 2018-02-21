package com.turn.ttorrent.network;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.channels.SocketChannel;

public class ConnectTask implements TimeoutAttachment, ReadAttachment {

  private long lastCommunicationTime;
  private final int myTimeoutMillis;
  private final String myHost;
  private final int myPort;
  private final ConnectionListener myConnectionListener;

  public ConnectTask(String host, int port, ConnectionListener connectionListener, long lastCommunicationTime, int timeoutMillis) {
    this.myHost = host;
    this.myPort = port;
    this.myConnectionListener = connectionListener;
    this.myTimeoutMillis = timeoutMillis;
    this.lastCommunicationTime = lastCommunicationTime;
  }

  public String getHost() {
    return myHost;
  }

  public int getPort() {
    return myPort;
  }

  @Override
  public ConnectionListener getConnectionListener() {
    return myConnectionListener;
  }

  @Override
  public String toString() {
    return "ConnectTask{" +
            "myHost='" + myHost + '\'' +
            ", myPort=" + myPort +
            '}';
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
    myConnectionListener.onError(channel, new SocketTimeoutException());
  }
}
