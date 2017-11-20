package com.turn.ttorrent.client.network;

import com.turn.ttorrent.client.Handshake;
import com.turn.ttorrent.client.SharedTorrent;
import com.turn.ttorrent.client.peer.PeerActivityListener;
import com.turn.ttorrent.client.peer.SharingPeer;
import com.turn.ttorrent.common.ConnectionUtils;
import com.turn.ttorrent.common.Peer;
import com.turn.ttorrent.common.PeersStorageProvider;
import com.turn.ttorrent.common.TorrentsStorageProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.text.ParseException;
import java.util.Arrays;

public class HandshakeReceiver implements DataProcessor {

  private static final Logger logger = LoggerFactory.getLogger(HandshakeReceiver.class);

  private final String uid;
  private final PeersStorageProvider peersStorageProvider;
  private final TorrentsStorageProvider torrentsStorageProvider;
  private ByteBuffer messageBytes;
  private int pstrLength;
  private final PeerActivityListener myPeerActivityListener;

  public HandshakeReceiver(String uid,
                           PeersStorageProvider peersStorageProvider,
                           TorrentsStorageProvider torrentsStorageProvider,
                           PeerActivityListener myPeerActivityListener) {
    this.uid = uid;
    this.peersStorageProvider = peersStorageProvider;
    this.torrentsStorageProvider = torrentsStorageProvider;
    this.myPeerActivityListener = myPeerActivityListener;
    this.pstrLength = -1;
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
        return new ShutdownProcessor(uid, peersStorageProvider);
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
      e.printStackTrace();
    }
    if (readBytes == -1) {
      return new ShutdownProcessor(uid, peersStorageProvider);
    }
    if (messageBytes.remaining() != 0) {
      return this;
    }
    Handshake hs;
    try {
      messageBytes.rewind();
      hs = Handshake.parse(messageBytes, pstrLength);
    } catch (ParseException e) {
      logger.debug("incorrect handshake message from " + socketChannel.toString(), e);
      return new ShutdownProcessor(uid, peersStorageProvider);
    }
    if (!torrentsStorageProvider.getTorrentsStorage().hasTorrent(hs.getHexInfoHash())) {
      logger.debug("peer {} try download torrent with hash {}, but it's unknown torrent for self",
              Arrays.toString(hs.getPeerId()),
              hs.getHexInfoHash());
      return new ShutdownProcessor(uid, peersStorageProvider);
    }

    logger.debug("get handshake {} from {}", Arrays.toString(messageBytes.array()), socketChannel);
    Peer peer = peersStorageProvider.getPeersStorage().getPeer(uid);
    ByteBuffer wrap = ByteBuffer.wrap(hs.getPeerId());
    wrap.rewind();
    peer.setPeerId(wrap);
    peer.setTorrentHash(hs.getHexInfoHash());
    logger.trace("set peer id to peer " + peer);
    ConnectionUtils.sendHandshake(socketChannel, hs.getInfoHash(), peersStorageProvider.getPeersStorage().getSelf().getPeerIdArray());
    SharedTorrent torrent = torrentsStorageProvider.getTorrentsStorage().getTorrent(hs.getHexInfoHash());
    SharingPeer sharingPeer = new SharingPeer(peer.getIp(), peer.getPort(), peer.getPeerId(), torrent);
    sharingPeer.register(torrent);
    sharingPeer.register(myPeerActivityListener);
    sharingPeer.bind(socketChannel, true);
    SharingPeer old = peersStorageProvider.getPeersStorage().tryAddSharingPeer(peer, sharingPeer);
    if (old != null) {
      logger.debug("$$$ already connected to " + peer);
      return new ShutdownProcessor(uid, peersStorageProvider);
    }
    return new WorkingReceiver(this.uid, peersStorageProvider, torrentsStorageProvider);
  }
}
