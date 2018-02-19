package com.turn.ttorrent.client.network;

public interface ReadAttachment {

  /**
   * @return connection listener, associated with key with current attachment
   */
  ConnectionListener getConnectionListener();
}
