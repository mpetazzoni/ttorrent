package com.turn.ttorrent.common;

import java.net.InetSocketAddress;

public class PeerUID {

  private final InetSocketAddress myAddress;
  private final String myTorrentHash;

  public PeerUID(InetSocketAddress address, String torrentHash) {
    myAddress = address;
    myTorrentHash = torrentHash;
  }

  public String getTorrentHash() {
    return myTorrentHash;
  }

  public InetSocketAddress getAddress() {
    return myAddress;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    PeerUID peerUID = (PeerUID) o;

    if (!myAddress.equals(peerUID.myAddress)) return false;
    return myTorrentHash.equals(peerUID.myTorrentHash);
  }

  @Override
  public int hashCode() {
    int result = myAddress.hashCode();
    result = 31 * result + myTorrentHash.hashCode();
    return result;
  }

  @Override
  public String toString() {
    return "PeerUID{" +
            "address=" + myAddress +
            ", torrent hash='" + myTorrentHash + '\'' +
            '}';
  }
}
