package com.turn.ttorrent.client.storage;

import java.io.File;
import java.io.IOException;

import com.turn.ttorrent.common.Torrent.TorrentFile;

/**
 * @author <a href="mailto:xianguang.zhou@outlook.com">Xianguang Zhou</a>
 */
public class FileStorageFactory implements StorageFactory {

	private final File parent;
	private final String parentPath;

	/**
	 * @param parent
	 *            The parent directory or location the torrent files.
	 * @throws IOException
	 *             If an I/O error occurs, which is possible because the
	 *             construction of the canonical pathname may require filesystem
	 *             queries
	 */
	public FileStorageFactory(File parent) throws IOException {
		if (parent == null || !parent.isDirectory()) {
			throw new IllegalArgumentException("Invalid parent directory!");
		}
		
		this.parent = parent;
		this.parentPath = parent.getCanonicalPath();
	}

	@Override
	public TorrentByteStorage create(TorrentFile torrentFile) throws IOException {
		File actual = new File(parent, torrentFile.file.getPath());

		if (!actual.getCanonicalPath().startsWith(parentPath)) {
			throw new SecurityException("Torrent file path attempted to break directory jail!");
		}

		actual.getParentFile().mkdirs();
		return new FileStorage(actual, torrentFile.size);
	}

}
