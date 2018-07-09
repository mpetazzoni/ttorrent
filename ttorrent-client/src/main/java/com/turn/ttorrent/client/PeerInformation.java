package com.turn.ttorrent.client;

import java.net.InetSocketAddress;

public interface PeerInformation {

  /**
   * @return {@link InetSocketAddress} of remote peer
   */
  InetSocketAddress getAddress();

  /**
   * @return id of current peer which the peers sent in the handshake
   */
  byte[] getId();

  /**
   * @return client identifier of current peer
   */
  String getClientIdentifier();

  /**
   * @return client version of current peer
   */
  int getClientVersion();

}
