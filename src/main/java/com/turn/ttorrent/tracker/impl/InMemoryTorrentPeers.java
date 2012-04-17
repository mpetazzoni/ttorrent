package com.turn.ttorrent.tracker.impl;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.turn.ttorrent.tracker.TorrentPeers;
import com.turn.ttorrent.tracker.TrackedPeer;

public class InMemoryTorrentPeers implements TorrentPeers {

	private final Map<String, TrackedPeer> peers;

	public InMemoryTorrentPeers() {
		peers = new ConcurrentHashMap<String, TrackedPeer>();
	}
	
	@Override
	public void put(String hexPeerId, TrackedPeer peer) {
		peers.put(hexPeerId, peer);
	}

	@Override
	public TrackedPeer get(String peerId) {
		return peers.get(peerId);
	}

	@Override
	public TrackedPeer remove(String peerId) {
		return peers.remove(peerId);
	}

	@Override
	public Collection<TrackedPeer> getPeers() {
		return peers.values();
	}

}
