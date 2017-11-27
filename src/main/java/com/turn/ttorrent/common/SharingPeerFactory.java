package com.turn.ttorrent.common;

import com.turn.ttorrent.client.SharedTorrent;
import com.turn.ttorrent.client.peer.SharingPeer;

import java.nio.ByteBuffer;

public interface SharingPeerFactory {

  SharingPeer createSharingPeer(String host, int port, ByteBuffer peerId, SharedTorrent torrent);

}
