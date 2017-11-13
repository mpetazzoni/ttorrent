package com.turn.ttorrent.client.network;

import com.turn.ttorrent.client.Client;
import com.turn.ttorrent.client.SharedTorrent;
import com.turn.ttorrent.common.Peer;
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
  private final ByteBuffer messageBytes;
  private final Client client;
  private int pstrLength;

  public WorkingReceiver(String uid, Client client) {
    this.peerUID = uid;
    this.client = client;
    this.messageBytes = ByteBuffer.allocate(2*1024*1024);
    this.pstrLength = -1;
  }

  @Override
  public DataProcessor processAndGetNext(SocketChannel socketChannel) throws IOException {
    logger.trace("received data from " + socketChannel.socket());
    messageBytes.limit(PeerMessage.MESSAGE_LENGTH_FIELD_SIZE);
    if (pstrLength == -1) {
      final int read = socketChannel.read(messageBytes);
      if (read < 0) {
        throw new EOFException(
                "Reached end-of-stream while reading size header");
      }
      if (messageBytes.hasRemaining()) {
        return this;
      }
      this.pstrLength = messageBytes.getInt(0);
    }

    if (PeerMessage.MESSAGE_LENGTH_FIELD_SIZE + this.pstrLength > messageBytes.capacity()){
      logger.debug("Proposed limit of {} is larger than capacity of {}",
              PeerMessage.MESSAGE_LENGTH_FIELD_SIZE + this.pstrLength, messageBytes.capacity());
    }
    messageBytes.limit(PeerMessage.MESSAGE_LENGTH_FIELD_SIZE + this.pstrLength);

    socketChannel.read(messageBytes);
    if (messageBytes.hasRemaining()) {
      return this;
    }
    messageBytes.rewind();
    try {
      Peer peer = client.peersStorage.getPeer(peerUID);
      SharedTorrent torrent = client.torrentsStorage.getTorrent(peer.getHexInfoHash());
      PeerMessage message = PeerMessage.parse(messageBytes, torrent);
      // TODO: 11/13/17 process message
    } catch (ParseException e) {
      logger.debug("{}", e.getMessage());
    }
    return this;
  }
}
