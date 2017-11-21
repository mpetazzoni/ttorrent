package com.turn.ttorrent.client.network;

import com.turn.ttorrent.client.SharedTorrent;
import com.turn.ttorrent.client.peer.SharingPeer;
import com.turn.ttorrent.common.PeerUID;
import com.turn.ttorrent.common.PeersStorageProvider;
import com.turn.ttorrent.common.TorrentsStorageProvider;
import com.turn.ttorrent.common.protocol.PeerMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.text.ParseException;

public class WorkingReceiver implements DataProcessor {

  private static final Logger logger = LoggerFactory.getLogger(WorkingReceiver.class);

  private final PeerUID myPeerUID;
  private final PeersStorageProvider peersStorageProvider;
  private final TorrentsStorageProvider torrentsStorageProvider;
  private final ByteBuffer messageBytes;
  private int pstrLength;

  public WorkingReceiver(PeerUID peerId, PeersStorageProvider peersStorageProvider, TorrentsStorageProvider torrentsStorageProvider) {
    this.myPeerUID = peerId;
    this.peersStorageProvider = peersStorageProvider;
    this.torrentsStorageProvider = torrentsStorageProvider;
    this.messageBytes = ByteBuffer.allocate(2 * 1024 * 1024);
    this.pstrLength = -1;
  }

  @Override
  public DataProcessor processAndGetNext(ByteChannel socketChannel) throws IOException {
    logger.trace("received data from channel", socketChannel);
    if (pstrLength == -1) {
      messageBytes.limit(PeerMessage.MESSAGE_LENGTH_FIELD_SIZE);
      final int read;
      try {
        read = socketChannel.read(messageBytes);
      } catch (IOException e) {
        return new ShutdownAndRemovePeerProcessor(myPeerUID, peersStorageProvider);
      }
      if (read < 0) {
        return new ShutdownAndRemovePeerProcessor(myPeerUID, peersStorageProvider);
      }
      if (messageBytes.hasRemaining()) {
        return this;
      }
      this.pstrLength = messageBytes.getInt(0);
    }

    if (PeerMessage.MESSAGE_LENGTH_FIELD_SIZE + this.pstrLength > messageBytes.capacity()) {
      logger.debug("Proposed limit of {} is larger than capacity of {}",
              PeerMessage.MESSAGE_LENGTH_FIELD_SIZE + this.pstrLength, messageBytes.capacity());
    }
    messageBytes.limit(PeerMessage.MESSAGE_LENGTH_FIELD_SIZE + this.pstrLength);

    try {
      socketChannel.read(messageBytes);
    } catch (IOException e) {
      return new ShutdownAndRemovePeerProcessor(myPeerUID, peersStorageProvider);
    }
    if (messageBytes.hasRemaining()) {
      return this;
    }
    messageBytes.rewind();
    this.pstrLength = -1;
    try {

      SharingPeer peer = peersStorageProvider.getPeersStorage().getSharingPeer(myPeerUID);

      SharedTorrent torrent = torrentsStorageProvider.getTorrentsStorage().getTorrent(peer.getHexInfoHash());
      if (torrent == null) {
        logger.debug("torrent with hash {} for peer {} doesn't found in storage. Maybe somebody deletes it manually", peer.getHexInfoHash(), peer);
        ShutdownAndRemovePeerProcessor shutdownAndRemovePeerProcessor = new ShutdownAndRemovePeerProcessor(myPeerUID, peersStorageProvider);
        return shutdownAndRemovePeerProcessor.processAndGetNext(socketChannel);
      }

      logger.trace("try parse message from {}. Torrent {}", peer, torrent);
      PeerMessage message = PeerMessage.parse(messageBytes, torrent);
      logger.trace("get message {} from {}", message, socketChannel);
      peer.handleMessage(message);
    } catch (ParseException e) {
      logger.debug("{}", e.getMessage());
    }
    this.messageBytes.rewind();
    return this;
  }
}
