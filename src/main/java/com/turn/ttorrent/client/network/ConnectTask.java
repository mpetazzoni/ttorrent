package com.turn.ttorrent.client.network;

public class ConnectTask {

  private final String myHost;
  private final int myPort;
  private final ConnectionListener myConnectionListener;

  public ConnectTask(String host, int port, ConnectionListener connectionListener) {
    this.myHost = host;
    this.myPort = port;
    this.myConnectionListener = connectionListener;
  }

  public String getHost() {
    return myHost;
  }

  public int getPort() {
    return myPort;
  }

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
}
