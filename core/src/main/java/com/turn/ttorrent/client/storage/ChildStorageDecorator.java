package com.turn.ttorrent.client.storage;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * @author <a href="mailto:xianguang.zhou@outlook.com">Xianguang Zhou</a>
 */
public class ChildStorageDecorator implements TorrentByteStorage {

	private final TorrentByteStorage storage;
	private final long offset;
	
	public ChildStorageDecorator(TorrentByteStorage storage) {
		this(storage, 0);
	}

	public ChildStorageDecorator(TorrentByteStorage storage, long offset) {
		this.storage = storage;
		this.offset = offset;
	}

	public long offset() {
		return this.offset;
	}

	@Override
	public long size() {
		return this.storage.size();
	}

	@Override
	public int read(ByteBuffer buffer, long offset) throws IOException {
		return this.storage.read(buffer, offset);
	}

	@Override
	public int write(ByteBuffer block, long offset) throws IOException {
		return this.storage.write(block, offset);
	}

	@Override
	public void close() throws IOException {
		this.storage.close();
	}

	@Override
	public void finish() throws IOException {
		this.storage.finish();
	}

	@Override
	public boolean isFinished() {
		return this.storage.isFinished();
	}
}
