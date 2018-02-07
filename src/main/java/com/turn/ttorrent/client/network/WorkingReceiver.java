package com.turn.ttorrent.client.network;

import com.turn.ttorrent.client.Context;
import com.turn.ttorrent.client.SharedTorrent;
import com.turn.ttorrent.client.peer.SharingPeer;
import com.turn.ttorrent.common.LoggerUtils;
import com.turn.ttorrent.common.PeerUID;
import com.turn.ttorrent.common.protocol.PeerMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.text.ParseException;
import java.util.Arrays;
import java.util.concurrent.RejectedExecutionException;

public class WorkingReceiver implements DataProcessor {

  private static final Logger logger = LoggerFactory.getLogger(WorkingReceiver.class);

  private final PeerUID myPeerUID;
  private final Context myContext;
  private final ByteBuffer messageBytes;
  private int pstrLength;

  public WorkingReceiver(PeerUID peerId,
                         Context context) {
    myPeerUID = peerId;
    myContext = context;
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
        LoggerUtils.warnAndDebugDetails(logger, "unable to read data from channel " + socketChannel, e);
        return new ShutdownAndRemovePeerProcessor(myPeerUID, myContext).processAndGetNext(socketChannel);
      }
      if (read < 0) {
        logger.debug("channel {} is closed by other peer", socketChannel);
        return new ShutdownAndRemovePeerProcessor(myPeerUID, myContext).processAndGetNext(socketChannel);
      }
      if (messageBytes.hasRemaining()) {
        return this;
      }
      this.pstrLength = messageBytes.getInt(0);
      logger.trace("read of message length finished, Message length is {}", this.pstrLength);
    }

    if (PeerMessage.MESSAGE_LENGTH_FIELD_SIZE + this.pstrLength > messageBytes.capacity()) {
      logger.warn("Proposed limit of {} is larger than capacity of {}",
              PeerMessage.MESSAGE_LENGTH_FIELD_SIZE + this.pstrLength, messageBytes.capacity());
      logger.warn("current bytes in buffer is {}", Arrays.toString(messageBytes.array()));
      logger.warn("Close connection with peer {}", myPeerUID);
      return new ShutdownAndRemovePeerProcessor(myPeerUID, myContext).processAndGetNext(socketChannel);
    }
    messageBytes.limit(PeerMessage.MESSAGE_LENGTH_FIELD_SIZE + this.pstrLength);

    logger.trace("try read data from {}", socketChannel);
    try {
      socketChannel.read(messageBytes);
    } catch (IOException e) {
      return new ShutdownAndRemovePeerProcessor(myPeerUID, myContext).processAndGetNext(socketChannel);
    }
    if (messageBytes.hasRemaining()) {
      logger.trace("buffer is not full, continue reading...");
      return this;
    }
    logger.trace("finished read data from {}", socketChannel);

    messageBytes.rewind();
    this.pstrLength = -1;

    final SharingPeer peer = myContext.getPeersStorage().getSharingPeer(myPeerUID);

    SharedTorrent torrent = myContext.getTorrentsStorage().getTorrent(peer.getHexInfoHash());
    if (torrent == null) {
      logger.debug("torrent with hash {} for peer {} doesn't found in storage. Maybe somebody deletes it manually", peer.getHexInfoHash(), peer);
      return new ShutdownAndRemovePeerProcessor(myPeerUID, myContext).processAndGetNext(socketChannel);
    }

    logger.trace("try parse message from {}. Torrent {}", peer, torrent);
    ByteBuffer bufferCopy = ByteBuffer.wrap(Arrays.copyOf(messageBytes.array(), messageBytes.limit()));

    this.messageBytes.rewind();
    final PeerMessage message;

    try {
      message = PeerMessage.parse(bufferCopy, torrent);
    } catch (ParseException e) {
      LoggerUtils.warnAndDebugDetails(logger, "incorrect message was received from peer {}", peer, e);
      return new ShutdownAndRemovePeerProcessor(myPeerUID, myContext).processAndGetNext(socketChannel);
    }

    logger.trace("get message {} from {}", message, socketChannel);

    try {
      myContext.getExecutor().submit(new Runnable() {
        @Override
        public void run() {
          final Thread currentThread = Thread.currentThread();
          final String oldName = currentThread.getName();
          currentThread.setName(oldName + " handle message for torrent " + myPeerUID.getTorrentHash() + " peer: " + peer.getHostIdentifier());
          peer.handleMessage(message);
          currentThread.setName(oldName);
        }
      });
    } catch (RejectedExecutionException e) {
      LoggerUtils.warnAndDebugDetails(logger, "task submit is failed. Reason: {}", e.getMessage(), e);
      return new ShutdownAndRemovePeerProcessor(myPeerUID, myContext).processAndGetNext(socketChannel);
    }
    return this;
  }

  @Override
  public DataProcessor handleError(ByteChannel socketChannel, Throwable e) throws IOException {
    return new ShutdownAndRemovePeerProcessor(myPeerUID, myContext).processAndGetNext(socketChannel);
  }
}
