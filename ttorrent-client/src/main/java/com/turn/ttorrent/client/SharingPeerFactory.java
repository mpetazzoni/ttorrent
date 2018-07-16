package com.turn.ttorrent.client;

import com.turn.ttorrent.client.peer.SharingPeer;

import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;

public interface SharingPeerFactory {

  SharingPeer createSharingPeer(String host,
                                int port,
                                ByteBuffer peerId,
                                SharedTorrent torrent,
                                ByteChannel channel,
                                String clientIdentifier,
                                int clientVersion);

}
