package com.turn.ttorrent.common;

import com.turn.ttorrent.client.SharedTorrent;
import com.turn.ttorrent.client.peer.PeerActivityListener;
import com.turn.ttorrent.client.peer.SharingPeer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.SocketException;
import java.nio.channels.ByteChannel;

public class SharingPeerRegisterImpl implements SharingPeerRegister {

  private final static Logger logger = LoggerFactory.getLogger(SharingPeerRegisterImpl.class);
  private final PeerActivityListener clientListener;

  public SharingPeerRegisterImpl(PeerActivityListener clientListener) {
    this.clientListener = clientListener;
  }

  @Override
  public void registerPeer(SharingPeer peer, SharedTorrent torrent, ByteChannel peerChannel) {
    try {
      peer.registerListenersAndBindChannel(peerChannel, torrent, clientListener);
    } catch (SocketException e) {
      LoggerUtils.errorAndDebugDetails(logger, "unable to register peer {}", peer, e);
    }
  }
}
