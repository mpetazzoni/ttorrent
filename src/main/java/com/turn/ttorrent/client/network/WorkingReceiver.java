package com.turn.ttorrent.client.network;

import com.turn.ttorrent.client.SharedTorrent;
import com.turn.ttorrent.common.Peer;
import com.turn.ttorrent.common.PeersStorageFactory;
import com.turn.ttorrent.common.TorrentsStorageFactory;
import com.turn.ttorrent.common.protocol.PeerMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
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
  public DataProcessor processAndGetNext(SocketChannel socketChannel) throws IOException {
    logger.trace("received data from " + socketChannel.socket());
    messageBytes.limit(PeerMessage.MESSAGE_LENGTH_FIELD_SIZE);
    if (pstrLength == -1) {
      final int read = socketChannel.read(messageBytes);
      if (read < 0) {
        return new ShutdownProcessor();
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

    socketChannel.read(messageBytes);
    if (messageBytes.hasRemaining()) {
      return this;
    }
    messageBytes.rewind();
    this.pstrLength = -1;
    try {
      Peer peer = peersStorageFactory.getPeersStorage().getPeer(peerUID);
      SharedTorrent torrent = torrentsStorageFactory.getTorrentsStorage().getTorrent(peer.getHexInfoHash());
      PeerMessage message = PeerMessage.parse(messageBytes, torrent);
      logger.trace("get message {} from {}", new Object[]{message, socketChannel.socket()});
      peersStorageFactory.getPeersStorage().getSharingPeer(peer).handleMessage(message);
    } catch (ParseException e) {
      logger.debug("{}", e.getMessage());
    }
    this.messageBytes.rewind();
    return this;
  }
}
