package com.turn.ttorrent.client;

import com.turn.ttorrent.common.TorrentHash;

import java.nio.channels.SocketChannel;

/**
 * @author Sergey.Pak
 * Date: 9/9/13
 * Time: 7:46 PM
 */
public interface TorrentConnectionListener {

  boolean hasTorrent(TorrentHash torrentHash);

  void handleNewPeerConnection(SocketChannel s, byte[] peerId, String hexInfoHash);
}
