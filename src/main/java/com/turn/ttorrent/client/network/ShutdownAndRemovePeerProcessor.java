package com.turn.ttorrent.client.network;

import com.turn.ttorrent.client.peer.SharingPeer;
import com.turn.ttorrent.common.PeerUID;
import com.turn.ttorrent.common.PeersStorage;
import com.turn.ttorrent.common.PeersStorageProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.ByteChannel;

public class ShutdownAndRemovePeerProcessor implements DataProcessor {

  private static final Logger logger = LoggerFactory.getLogger(ShutdownAndRemovePeerProcessor.class);

  private final PeerUID myPeerUID;
  private final PeersStorageProvider peersStorageProvider;

  public ShutdownAndRemovePeerProcessor(PeerUID peerId, PeersStorageProvider peersStorageProvider) {
    this.myPeerUID = peerId;
    this.peersStorageProvider = peersStorageProvider;
  }

  @Override
  public DataProcessor processAndGetNext(ByteChannel socketChannel) throws IOException {
    DataProcessorUtil.closeChannelIfOpen(logger, socketChannel);
    logger.debug("try remove and unbind peer. Peer UID - {}", myPeerUID);
    removePeer();
    return null;
  }

  private void removePeer() {
    PeersStorage peersStorage = peersStorageProvider.getPeersStorage();
    SharingPeer removedPeer = peersStorage.removeSharingPeer(myPeerUID);
    if (removedPeer == null) {
      logger.info("try to shutdown peer with id {}, but it is not found in storage", myPeerUID);
      return;
    }
    removedPeer.unbind(true);
  }

  @Override
  public DataProcessor handleError(ByteChannel socketChannel, Throwable e) throws IOException {
    return processAndGetNext(socketChannel);
  }
}
