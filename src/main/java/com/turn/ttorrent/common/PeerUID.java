package com.turn.ttorrent.common;

public class PeerUID {

  private final String peerId;
  private final String torrentHash;

  public PeerUID(String peerId, String torrentHash) {
    this.peerId = peerId;
    this.torrentHash = torrentHash;
  }

  public String getTorrentHash() {
    return torrentHash;
  }

  public String getPeerId() {
    return peerId;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    PeerUID peerUID = (PeerUID) o;

    if (!peerId.equals(peerUID.peerId)) return false;
    return torrentHash.equals(peerUID.torrentHash);
  }

  @Override
  public int hashCode() {
    int result = peerId.hashCode();
    result = 31 * result + torrentHash.hashCode();
    return result;
  }

  @Override
  public String toString() {
    return "PeerUID{" +
            "peerId='" + peerId + '\'' +
            ", torrentHash='" + torrentHash + '\'' +
            '}';
  }
}
