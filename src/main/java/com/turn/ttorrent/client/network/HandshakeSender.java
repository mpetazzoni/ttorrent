package com.turn.ttorrent.client.network;

import com.turn.ttorrent.client.Handshake;
import com.turn.ttorrent.client.peer.PeerActivityListener;
import com.turn.ttorrent.common.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.util.Arrays;

public class HandshakeSender implements DataProcessor {

  private static final Logger logger = LoggerFactory.getLogger(HandshakeSender.class);

  private final TorrentHash myTorrentHash;
  private final PeersStorageProvider myPeersStorageProvider;
  private final TorrentsStorageProvider myTorrentsStorageProvider;
  private final SharingPeerRegister mySharingPeerRegister;
  private final InetSocketAddress mySendAddress;
  private final SharingPeerFactory mySharingPeerFactory;

  public HandshakeSender(TorrentHash torrentHash,
                         PeersStorageProvider peersStorageProvider,
                         TorrentsStorageProvider torrentsStorageProvider,
                         SharingPeerRegister sharingPeerRegister,
                         InetSocketAddress sendAddress,
                         SharingPeerFactory sharingPeerFactory) {
    this.myTorrentHash = torrentHash;
    this.myPeersStorageProvider = peersStorageProvider;
    this.myTorrentsStorageProvider = torrentsStorageProvider;
    this.mySharingPeerRegister = sharingPeerRegister;
    this.mySendAddress = sendAddress;
    this.mySharingPeerFactory = sharingPeerFactory;
  }

  @Override
  public DataProcessor processAndGetNext(ByteChannel socketChannel) throws IOException {

    Peer self = myPeersStorageProvider.getPeersStorage().getSelf();
    Handshake handshake = Handshake.craft(myTorrentHash.getInfoHash(), self.getPeerIdArray());
    if (handshake == null) {
      logger.warn("can not craft handshake message. Self peer id is {}, torrent hash is {}",
              Arrays.toString(self.getPeerIdArray()),
              Arrays.toString(myTorrentHash.getInfoHash()));
      return new ShutdownProcessor();
    }
    ByteBuffer messageToSend = ByteBuffer.wrap(handshake.getData().array());
    logger.trace("try send handshake {} to {}", handshake, socketChannel);
    while (messageToSend.hasRemaining()) {
      socketChannel.write(messageToSend);
    }
    return new HandshakeReceiver(
            myPeersStorageProvider,
            myTorrentsStorageProvider,
            mySharingPeerFactory,
            mySharingPeerRegister,
            mySendAddress.getHostName(),
            mySendAddress.getPort(),
            true);
  }

  @Override
  public DataProcessor handleError(ByteChannel socketChannel, Throwable e) throws IOException {
    return new ShutdownProcessor().processAndGetNext(socketChannel);
  }
}
