package com.turn.ttorrent.client.network;

import com.turn.ttorrent.client.Context;
import com.turn.ttorrent.client.SharedTorrent;
import com.turn.ttorrent.client.peer.SharingPeer;
import com.turn.ttorrent.common.LoggerUtils;
import com.turn.ttorrent.common.PeerUID;
import com.turn.ttorrent.common.TorrentLoggerFactory;
import com.turn.ttorrent.common.protocol.PeerMessage;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.text.ParseException;
import java.util.Arrays;
import java.util.concurrent.RejectedExecutionException;

public class WorkingReceiver implements DataProcessor {

  private static final Logger logger = TorrentLoggerFactory.getLogger();
  //16 bytes is sufficient for all torrents messages except bitfield and piece.
  //So piece and bitfield have dynamic size because bytebuffer for this messages will be allocated after get message length
  private static final int DEF_BUFFER_SIZE = 16;
  private static final int MAX_MESSAGE_SIZE = 2 * 1024 * 1024;

  private final PeerUID myPeerUID;
  private final Context myContext;
  @NotNull
  private ByteBuffer messageBytes;
  private int pstrLength;

  WorkingReceiver(PeerUID peerId,
                         Context context) {
    myPeerUID = peerId;
    myContext = context;

    this.messageBytes = ByteBuffer.allocate(DEF_BUFFER_SIZE);
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
        //Some clients close connection so that java throws IOException "An existing connection was forcibly closed by the remote host"
        logger.debug("unable to read data from channel " + socketChannel, e);
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

      if (this.pstrLength > MAX_MESSAGE_SIZE) {
        logger.warn("Proposed limit of {} is larger than max message size {}",
                PeerMessage.MESSAGE_LENGTH_FIELD_SIZE + this.pstrLength, MAX_MESSAGE_SIZE);
        logger.warn("current bytes in buffer is {}", Arrays.toString(messageBytes.array()));
        logger.warn("Close connection with peer {}", myPeerUID);
        return new ShutdownAndRemovePeerProcessor(myPeerUID, myContext).processAndGetNext(socketChannel);
      }
    }

    if (PeerMessage.MESSAGE_LENGTH_FIELD_SIZE + this.pstrLength > messageBytes.capacity()) {
      ByteBuffer old = messageBytes;
      old.rewind();
      messageBytes = ByteBuffer.allocate(PeerMessage.MESSAGE_LENGTH_FIELD_SIZE + this.pstrLength);
      messageBytes.put(old);
    }

    messageBytes.limit(PeerMessage.MESSAGE_LENGTH_FIELD_SIZE + this.pstrLength);

    logger.trace("try read data from {}", socketChannel);
    int readBytes;
    try {
      readBytes = socketChannel.read(messageBytes);
    } catch (IOException e) {
      return new ShutdownAndRemovePeerProcessor(myPeerUID, myContext).processAndGetNext(socketChannel);
    }
    if (readBytes < 0) {
      logger.debug("channel {} is closed by other peer", socketChannel);
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

    final String hexInfoHash = peer.getHexInfoHash();
    SharedTorrent torrent = myContext.getTorrentsStorage().getTorrent(hexInfoHash);
    if (torrent == null || !myContext.getTorrentsStorage().hasTorrent(hexInfoHash)) {
      logger.debug("torrent with hash {} for peer {} doesn't found in storage. Maybe somebody deletes it manually", hexInfoHash, peer);
      return new ShutdownAndRemovePeerProcessor(myPeerUID, myContext).processAndGetNext(socketChannel);
    }

    logger.trace("try parse message from {}. Torrent {}", peer, torrent);
    ByteBuffer bufferCopy = ByteBuffer.wrap(Arrays.copyOf(messageBytes.array(), messageBytes.limit()));

    this.messageBytes = ByteBuffer.allocate(DEF_BUFFER_SIZE);
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
          try {
            currentThread.setName(oldName + " handle message for torrent " + myPeerUID.getTorrentHash() + " peer: " + peer.getHostIdentifier());
            peer.handleMessage(message);
          } catch (Throwable e) {
            LoggerUtils.warnAndDebugDetails(logger, "unhandled exception {} in executor task (handleMessage)", e.toString(), e);
          } finally {
            currentThread.setName(oldName);
          }

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
