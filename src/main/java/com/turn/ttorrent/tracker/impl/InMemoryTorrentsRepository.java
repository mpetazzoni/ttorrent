package com.turn.ttorrent.tracker.impl;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.turn.ttorrent.tracker.TorrentsRepository;
import com.turn.ttorrent.tracker.TrackedTorrent;

public class InMemoryTorrentsRepository implements TorrentsRepository {

	private final Map<String, TrackedTorrent> torrents;
	
	public InMemoryTorrentsRepository() {
		torrents = new ConcurrentHashMap<String, TrackedTorrent>();
	}

	@Override
	public TrackedTorrent get(String hexInfoHash) {
		return torrents.get(hexInfoHash);
	}

	@Override
	public void put(String hexInfoHash, TrackedTorrent torrent) {
		torrents.put(hexInfoHash, torrent);
	}

	@Override
	public TrackedTorrent remove(String hexInfoHash) {
		return torrents.remove(hexInfoHash);
	}

	@Override
	public Collection<TrackedTorrent> getTorrents() {
		return torrents.values();
	}
	
}
