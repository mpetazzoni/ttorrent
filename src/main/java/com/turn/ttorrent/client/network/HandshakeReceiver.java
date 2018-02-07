package com.turn.ttorrent.client.network;

import com.turn.ttorrent.client.Context;
import com.turn.ttorrent.client.Handshake;
import com.turn.ttorrent.client.SharedTorrent;
import com.turn.ttorrent.client.peer.SharingPeer;
import com.turn.ttorrent.common.ConnectionUtils;
import com.turn.ttorrent.common.LoggerUtils;
import com.turn.ttorrent.common.PeerUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.text.ParseException;
import java.util.Arrays;

public class HandshakeReceiver implements DataProcessor {

  private static final Logger logger = LoggerFactory.getLogger(HandshakeReceiver.class);

  private final Context myContext;
  private final String myHostAddress;
  private final int myPort;
  private final boolean myIsOutgoingConnection;
  private ByteBuffer messageBytes;
  private int pstrLength;

  public HandshakeReceiver(Context context,
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

    SharedTorrent torrent = myContext.getTorrentsStorage().getTorrent(hs.getHexInfoHash());

    if (torrent == null) {
      logger.debug("peer {} tries to download unknown torrent {}",
              Arrays.toString(hs.getPeerId()),
              hs.getHexInfoHash());
      return new ShutdownProcessor().processAndGetNext(socketChannel);
    }

    logger.debug("got handshake {} from {}", Arrays.toString(messageBytes.array()), socketChannel);

    SharingPeer sharingPeer =
            myContext.createSharingPeer(myHostAddress, myPort, ByteBuffer.wrap(hs.getPeerId()), torrent, socketChannel);
    PeerUID peerUID = new PeerUID(sharingPeer.getAddress(), hs.getHexInfoHash());

    SharingPeer old = myContext.getPeersStorage().putIfAbsent(peerUID, sharingPeer);
    if (old != null) {
      logger.debug("Already connected to old peer {}, close current connection with {}", old, sharingPeer);
      return new ShutdownProcessor().processAndGetNext(socketChannel);
    }

    if (!myIsOutgoingConnection) {
      logger.debug("send handshake to {}", socketChannel);
      try {
        ConnectionUtils.sendHandshake(socketChannel, hs.getInfoHash(), myContext.getPeersStorage().getSelf().getPeerIdArray());
      } catch (IOException e) {
        LoggerUtils.warnAndDebugDetails(logger, "error in sending handshake to {}", socketChannel, e);
        return new ShutdownAndRemovePeerProcessor(peerUID, myContext);
      }
    }

    logger.info("setup new connection with {}", sharingPeer);

    sharingPeer.onConnectionEstablished();

    return new WorkingReceiver(peerUID, myContext);
  }

  private Handshake parseHandshake(String socketChannelForLog) throws IOException {
    try {
      messageBytes.rewind();
      return Handshake.parse(messageBytes, pstrLength);
    } catch (ParseException e) {
      logger.debug("incorrect handshake message from " + socketChannelForLog, e);
    }
    return null;
  }

  @Override
  public DataProcessor handleError(ByteChannel socketChannel, Throwable e) throws IOException {
    return new ShutdownProcessor().processAndGetNext(socketChannel);
  }
}
