package com.turn.ttorrent.tracker;

import java.util.Collection;

public interface TorrentsRepository {

	TrackedTorrent get(String hexInfoHash);

	void put(String hexInfoHash, TrackedTorrent torrent);

	TrackedTorrent remove(String hexInfoHash);

	Collection<TrackedTorrent> getTorrents();

}
