package com.turn.ttorrent.client.network;

import com.turn.ttorrent.client.Handshake;
import com.turn.ttorrent.common.ConnectionUtils;
import com.turn.ttorrent.common.Peer;
import com.turn.ttorrent.common.PeersStorageFactory;
import com.turn.ttorrent.common.TorrentsStorageFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.text.ParseException;

public class HandshakeReceiver implements DataProcessor {

  private static final Logger logger = LoggerFactory.getLogger(HandshakeReceiver.class);

  private final String uid;
  private final PeersStorageFactory peersStorageFactory;
  private final TorrentsStorageFactory torrentsStorageFactory;
  private ByteBuffer messageBytes;
  private int pstrLength;

  public HandshakeReceiver(String uid, PeersStorageFactory peersStorageFactory, TorrentsStorageFactory torrentsStorageFactory) {
    this.uid = uid;
    this.peersStorageFactory = peersStorageFactory;
    this.torrentsStorageFactory = torrentsStorageFactory;
    this.pstrLength = -1;
  }

  @Override
  public DataProcessor processAndGetNext(SocketChannel socketChannel) throws IOException {
    if (pstrLength == -1) {
      ByteBuffer len = ByteBuffer.allocate(1);
      final int readBytes = socketChannel.read(len);
      if (readBytes == -1) {
        throw new IOException("Handshake size read underrrun");
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
    socketChannel.read(messageBytes);
    if (messageBytes.remaining() != 0) {
      return this;
    }
    Handshake hs;
    try {
      messageBytes.rewind();
      hs = Handshake.parse(messageBytes, pstrLength);
    } catch (ParseException e) {
      logger.debug("incorrect handshake message from " + socketChannel.getLocalAddress() + ". Close channel", e);
      socketChannel.close();
      return this;// TODO: 11/13/17 return shutdown receiver, drop peer from collections
    }
    if (!torrentsStorageFactory.getTorrentsStorage().hasTorrent(hs.getHexInfoHash())) {
      socketChannel.close();
      return this;// TODO: 11/13/17 return shutdown receiver, drop peer from collections
    }

    Peer peer = peersStorageFactory.getPeersStorage().getPeer(uid);
    logger.trace("set peer id to peer " + peer);
    peer.setPeerId(ByteBuffer.wrap(hs.getPeerId()));
    peer.setTorrentHash(hs.getHexInfoHash());
    ConnectionUtils.sendHandshake(socketChannel, hs.getInfoHash(), peersStorageFactory.getPeersStorage().getSelf().getPeerIdArray());
    return new WorkingReceiver(this.uid, peersStorageFactory, torrentsStorageFactory);
  }
}
