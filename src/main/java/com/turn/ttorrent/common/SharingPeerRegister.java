package com.turn.ttorrent.common;

import com.turn.ttorrent.client.SharedTorrent;
import com.turn.ttorrent.client.peer.SharingPeer;

import java.nio.channels.ByteChannel;

public interface SharingPeerRegister {

  void registerPeer(SharingPeer peer, SharedTorrent torrent, ByteChannel peerChannel);

}
