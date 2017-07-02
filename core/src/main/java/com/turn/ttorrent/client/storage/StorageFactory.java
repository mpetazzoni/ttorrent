package com.turn.ttorrent.client.storage;

import java.io.IOException;

import com.turn.ttorrent.common.Torrent;

/**
 * @author <a href="mailto:xianguang.zhou@outlook.com">Xianguang Zhou</a>
 */
public interface StorageFactory {
	TorrentByteStorage create(Torrent.TorrentFile torrentFile) throws IOException;
}
