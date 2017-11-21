package com.turn.ttorrent.client.network;

import com.turn.ttorrent.common.TorrentHash;

public class ConnectTask {

  private final String myHost;
  private final int myPort;
  private final TorrentHash myTorrentHash;

  public ConnectTask(String host, int port, TorrentHash torrentHash) {
    this.myHost = host;
    this.myPort = port;
    this.myTorrentHash = torrentHash;
  }

  public String getHost() {
    return myHost;
  }

  public int getPort() {
    return myPort;
  }

  public byte[] getTorrentHashBytes() {
    return myTorrentHash.getInfoHash();
  }

  public String getTorrentHashHex() {
    return myTorrentHash.getHexInfoHash();
  }

  @Override
  public String toString() {
    return "ConnectTask{" +
            "myHost='" + myHost + '\'' +
            ", myPort=" + myPort +
            ", myTorrentHash='" + myTorrentHash + '\'' +
            '}';
  }
}
