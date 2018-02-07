package com.turn.ttorrent.client.network;

import com.turn.ttorrent.client.Context;
import com.turn.ttorrent.client.Handshake;
import com.turn.ttorrent.common.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;

public class HandshakeSender implements DataProcessor {

  private static final Logger logger = LoggerFactory.getLogger(HandshakeSender.class);

  private final TorrentHash myTorrentHash;
  private final InetSocketAddress mySendAddress;
  private final Context myContext;

  public HandshakeSender(TorrentHash torrentHash,
                         InetSocketAddress sendAddress,
                         Context context) {
    myTorrentHash = torrentHash;
    mySendAddress = sendAddress;
    myContext = context;
  }

  @Override
  public DataProcessor processAndGetNext(ByteChannel socketChannel) throws IOException {

    Peer self = myContext.getPeersStorage().getSelf();
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
            myContext,
            mySendAddress.getHostName(),
            mySendAddress.getPort(),
            true);
  }

  @Override
  public DataProcessor handleError(ByteChannel socketChannel, Throwable e) throws IOException {
    return new ShutdownProcessor().processAndGetNext(socketChannel);
  }
}
