package com.turn.ttorrent.client.network;

import com.turn.ttorrent.client.Context;
import com.turn.ttorrent.client.Handshake;
import com.turn.ttorrent.client.LoadedTorrent;
import com.turn.ttorrent.client.SharedTorrent;
import com.turn.ttorrent.client.peer.SharingPeer;
import com.turn.ttorrent.common.LoggerUtils;
import com.turn.ttorrent.common.PeerUID;
import com.turn.ttorrent.common.TorrentLoggerFactory;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.text.ParseException;
import java.util.Arrays;
import java.util.concurrent.RejectedExecutionException;

public class HandshakeReceiver implements DataProcessor {

  private static final Logger logger = TorrentLoggerFactory.getLogger();

  private final Context myContext;
  private final String myHostAddress;
  private final int myPort;
  private final boolean myIsOutgoingConnection;
  private ByteBuffer messageBytes;
  private int pstrLength;

  HandshakeReceiver(Context context,
                    String hostAddress,
                    int port,
                    boolean isOutgoingListener) {
    myContext = context;
    myHostAddress = hostAddress;
    myPort = port;
    this.pstrLength = -1;
    this.myIsOutgoingConnection = isOutgoingListener;
  }

  @Override
  public DataProcessor processAndGetNext(ByteChannel socketChannel) throws IOException {

    if (pstrLength == -1) {
      ByteBuffer len = ByteBuffer.allocate(1);
      int readBytes = -1;
      try {
        readBytes = socketChannel.read(len);
      } catch (IOException ignored) {
      }
      if (readBytes == -1) {
        return new ShutdownProcessor().processAndGetNext(socketChannel);
      }
      if (readBytes == 0) {
        return this;
      }
      len.rewind();
      byte pstrLen = len.get();
      this.pstrLength = pstrLen;
      messageBytes = ByteBuffer.allocate(this.pstrLength + Handshake.BASE_HANDSHAKE_LENGTH);
      messageBytes.put(pstrLen);
    }
    int readBytes = -1;
    try {
      readBytes = socketChannel.read(messageBytes);
    } catch (IOException e) {
      LoggerUtils.warnAndDebugDetails(logger, "unable to read data from {}", socketChannel, e);
    }
    if (readBytes == -1) {
      return new ShutdownProcessor().processAndGetNext(socketChannel);
    }
    if (messageBytes.remaining() != 0) {
      return this;
    }
    Handshake hs = parseHandshake(socketChannel.toString());

    if (hs == null) {
      return new ShutdownProcessor().processAndGetNext(socketChannel);
    }

    final LoadedTorrent announceableTorrent = myContext.getTorrentsStorage().getLoadedTorrent(hs.getHexInfoHash());

    if (announceableTorrent == null) {
      logger.debug("Announceable torrent {} is not found in storage", hs.getHexInfoHash());
      return new ShutdownProcessor().processAndGetNext(socketChannel);
    }

    SharedTorrent torrent;
    try {
      torrent = myContext.getTorrentLoader().loadTorrent(announceableTorrent);
    } catch (IllegalStateException e) {
      return new ShutdownProcessor().processAndGetNext(socketChannel);
    } catch(Exception e) {
      LoggerUtils.warnWithMessageAndDebugDetails(logger, "cannot load torrent {}", hs.getHexInfoHash(), e);
      return new ShutdownProcessor().processAndGetNext(socketChannel);
    }

    logger.trace("got handshake {} from {}", Arrays.toString(messageBytes.array()), socketChannel);

    String clientTypeVersion = new String(Arrays.copyOf(hs.getPeerId(), 8));
    String clientType = clientTypeVersion.substring(1, 3);
    int clientVersion = 0;
    try {
      clientVersion = Integer.parseInt(clientTypeVersion.substring(3, 7));
    } catch (NumberFormatException ignored) {}
    final SharingPeer sharingPeer =
            myContext.createSharingPeer(myHostAddress,
                    myPort,
                    ByteBuffer.wrap(hs.getPeerId()),
                    torrent,
                    socketChannel,
                    clientType,
                    clientVersion);
    PeerUID peerUID = new PeerUID(sharingPeer.getAddress(), hs.getHexInfoHash());

    SharingPeer old = myContext.getPeersStorage().putIfAbsent(peerUID, sharingPeer);
    if (old != null) {
      logger.debug("Already connected to old peer {}, close current connection with {}", old, sharingPeer);
      return new ShutdownProcessor().processAndGetNext(socketChannel);
    }

    // If I am not a leecher
    if (!myIsOutgoingConnection) {
      logger.trace("send handshake to {}", socketChannel);
      try {
        final Handshake craft = Handshake.craft(hs.getInfoHash(), myContext.getPeersStorage().getSelf().getPeerIdArray());
        socketChannel.write(craft.getData());
      } catch (IOException e) {
        LoggerUtils.warnAndDebugDetails(logger, "error in sending handshake to {}", socketChannel, e);
        return new ShutdownAndRemovePeerProcessor(peerUID, myContext);
      }
    }

    logger.debug("setup new connection with {}", sharingPeer);

    try {
      myContext.getExecutor().submit(new Runnable() {
        @Override
        public void run() {
          try {
            sharingPeer.onConnectionEstablished();
          } catch (Throwable e) {
            LoggerUtils.warnAndDebugDetails(logger, "unhandled exception {} in executor task (onConnectionEstablished)", e.toString(), e);
          }
        }
      });
      torrent.addConnectedPeer(sharingPeer);
    } catch (RejectedExecutionException e) {
      LoggerUtils.warnAndDebugDetails(logger, "task 'onConnectionEstablished' submit is failed. Reason: {}", e.getMessage(), e);
      return new ShutdownAndRemovePeerProcessor(peerUID, myContext).processAndGetNext(socketChannel);
    }

    return new WorkingReceiver(peerUID, myContext);
  }

  private Handshake parseHandshake(String socketChannelForLog) throws IOException {
    try {
      messageBytes.rewind();
      return Handshake.parse(messageBytes, pstrLength);
    } catch (ParseException e) {
      logger.info("incorrect handshake message from " + socketChannelForLog, e);
    }
    return null;
  }

  @Override
  public DataProcessor handleError(ByteChannel socketChannel, Throwable e) throws IOException {
    return new ShutdownProcessor().processAndGetNext(socketChannel);
  }
}
