package com.turn.ttorrent.tracker;

import java.util.Collection;

public interface TorrentPeers {

	void put(String hexPeerId, TrackedPeer peer);

	TrackedPeer get(String peerId);

	TrackedPeer remove(String peerId);

	Collection<TrackedPeer> getPeers();

}
