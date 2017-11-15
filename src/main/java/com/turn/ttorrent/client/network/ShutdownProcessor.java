package com.turn.ttorrent.client.network;

import com.turn.ttorrent.client.peer.SharingPeer;
import com.turn.ttorrent.common.Peer;
import com.turn.ttorrent.common.PeersStorage;
import com.turn.ttorrent.common.PeersStorageFactory;

import java.io.IOException;
import java.nio.channels.ByteChannel;

public class ShutdownProcessor implements DataProcessor {

  private final String uid;
  private final PeersStorageFactory peersStorageFactory;

  public ShutdownProcessor(String uid, PeersStorageFactory peersStorageFactory) {
    this.uid = uid;
    this.peersStorageFactory = peersStorageFactory;
  }

  @Override
  public DataProcessor processAndGetNext(ByteChannel socketChannel) throws IOException {
    if (socketChannel.isOpen()) {
      socketChannel.close();
      removePeers();
    }
    return this;
  }

  private void removePeers() {
    PeersStorage peersStorage = peersStorageFactory.getPeersStorage();
    Peer peer = peersStorage.removePeer(uid);
    if (peer == null) {
      return;
    }
    SharingPeer sharingPeer = peersStorage.removeSharingPeer(peer);
    if (sharingPeer == null) {
      return;
    }
    sharingPeer.unbind(true);
  }
}
