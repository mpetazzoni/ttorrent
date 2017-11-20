package com.turn.ttorrent.client.network;

import com.turn.ttorrent.client.SharedTorrent;
import com.turn.ttorrent.common.Peer;
import com.turn.ttorrent.common.PeersStorageFactory;
import com.turn.ttorrent.common.TorrentsStorageFactory;
import com.turn.ttorrent.common.protocol.PeerMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.text.ParseException;

public class WorkingReceiver implements DataProcessor {

  private static final Logger logger = LoggerFactory.getLogger(WorkingReceiver.class);

  private final String peerUID;
  private final PeersStorageFactory peersStorageFactory;
  private final TorrentsStorageFactory torrentsStorageFactory;
  private final ByteBuffer messageBytes;
  private int pstrLength;

  public WorkingReceiver(String uid, PeersStorageFactory peersStorageFactory, TorrentsStorageFactory torrentsStorageFactory) {
    this.peerUID = uid;
    this.peersStorageFactory = peersStorageFactory;
    this.torrentsStorageFactory = torrentsStorageFactory;
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
        return new ShutdownProcessor(peerUID, peersStorageFactory);
      }
      if (read < 0) {
        return new ShutdownProcessor(peerUID, peersStorageFactory);
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
      return new ShutdownProcessor(peerUID, peersStorageFactory);
    }
    if (messageBytes.hasRemaining()) {
      return this;
    }
    messageBytes.rewind();
    this.pstrLength = -1;
    try {
      Peer peer = peersStorageFactory.getPeersStorage().getPeer(peerUID);
      SharedTorrent torrent = torrentsStorageFactory.getTorrentsStorage().getTorrent(peer.getHexInfoHash());
      if (torrent == null) {
        //torrent doesn't seed more. Maybe somebody delete it manually. shutdown channel immediately.
        ShutdownProcessor shutdownProcessor = new ShutdownProcessor(peerUID, peersStorageFactory);
        return shutdownProcessor.processAndGetNext(socketChannel);
      }
      logger.trace("try parse message from {}. Torrent {}", peer, torrent);
      PeerMessage message = PeerMessage.parse(messageBytes, torrent);
      logger.trace("get message {} from {}", message, socketChannel);
      peersStorageFactory.getPeersStorage().getSharingPeer(peer).handleMessage(message);
    } catch (ParseException e) {
      logger.debug("{}", e.getMessage());
    }
    this.messageBytes.rewind();
    return this;
  }
}
