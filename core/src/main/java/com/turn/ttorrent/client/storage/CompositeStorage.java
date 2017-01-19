/**
 * Copyright (C) 2011-2012 Turn, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.turn.ttorrent.client.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Multi-file torrent byte storage.
 *
 * <p>
 * This implementation of the torrent byte storage provides support for
 * multi-file torrents and completely abstracts the read/write operations from
 * the notion of different files. The byte storage is represented as one
 * continuous byte storage, directly accessible by offset regardless of which
 * file this offset lands.
 * </p>
 *
 * @author mpetazzoni
 * @author dgiffin
 * @author <a href="mailto:xianguang.zhou@outlook.com">Xianguang Zhou</a>
 */
public class CompositeStorage implements TorrentByteStorage {

	private static final Logger logger =
		LoggerFactory.getLogger(CompositeStorage.class);

	private final List<ChildStorageDecorator> children;
	private final long size;

	/**
	 * Initialize a new multi-file torrent byte storage.
	 *
	 * @param children The list of individual {@link ChildStorageDecorator}
	 * objects making up the torrent.
	 * @param size The total size of the torrent data, in bytes.
	 */
	public CompositeStorage(List<ChildStorageDecorator> children,
		long size) {
		this.children = children;
		this.size = size;

		logger.info("Initialized torrent byte storage on {} file(s) " +
			"({} total byte(s)).", children.size(), size);
	}

	@Override
	public long size() {
		return this.size;
	}

	@Override
	public int read(ByteBuffer buffer, long offset) throws IOException {
		int requested = buffer.remaining();
		int bytes = 0;

		for (StorageOffset so : this.select(offset, requested)) {
			// TODO: remove cast to int when large ByteBuffer support is
			// implemented in Java.
			buffer.limit((int)(bytes + so.length));
			bytes += so.storage.read(buffer, so.offset);
		}

		if (bytes < requested) {
			throw new IOException("Storage collection read underrun!");
		}

		return bytes;
	}

	@Override
	public int write(ByteBuffer buffer, long offset) throws IOException {
		int requested = buffer.remaining();

		int bytes = 0;

		for (StorageOffset so : this.select(offset, requested)) {
			buffer.limit(bytes + (int)so.length);
			bytes += so.storage.write(buffer, so.offset);
		}

		if (bytes < requested) {
			throw new IOException("Storage collection write underrun!");
		}

		return bytes;
	}

	@Override
	public void close() throws IOException {
		for (TorrentByteStorage child : this.children) {
			child.close();
		}
	}

	@Override
	public void finish() throws IOException {
		for (TorrentByteStorage child : this.children) {
			child.finish();
		}
	}

	@Override
	public boolean isFinished() {
		for (TorrentByteStorage child : this.children) {
			if (!child.isFinished()) {
				return false;
			}
		}

		return true;
	}

	/**
	 * Storage operation details holder.
	 *
	 * <p>
	 * This simple inner class holds the details for a read or write operation
	 * on one of the underlying {@link ChildStorageDecorator}s.
	 * </p>
	 *
	 * @author dgiffin
	 * @author mpetazzoni
	 */
	private static class StorageOffset {

		public final ChildStorageDecorator storage;
		public final long offset;
		public final long length;

		StorageOffset(ChildStorageDecorator storage, long offset, long length) {
			this.storage = storage;
			this.offset = offset;
			this.length = length;
		}
	};

	/**
	 * Select the group of files impacted by an operation.
	 *
	 * <p>
	 * This function selects which files are impacted by a read or write
	 * operation, with their respective relative offset and chunk length.
	 * </p>
	 *
	 * @param offset The offset of the operation, in bytes, relative to the
	 * complete byte storage.
	 * @param length The number of bytes to read or write.
	 * @return A list of {@link StorageOffset} objects representing the {@link
	 * ChildStorageDecorator}s impacted by the operation, bundled with their
	 * respective relative offset and number of bytes to read or write.
	 * @throws IllegalArgumentException If the offset and length go over the
	 * byte storage size.
	 * @throws IllegalStateException If the files registered with this byte
	 * storage can't accommodate the request (should not happen, really).
	 */
	private List<StorageOffset> select(long offset, long length) {
		if (offset + length > this.size) {
			throw new IllegalArgumentException("Buffer overrun (" +
				offset + " + " + length + " > " + this.size + ") !");
		}

		List<StorageOffset> selected = new LinkedList<StorageOffset>();
		long bytes = 0;

		for (ChildStorageDecorator child : this.children) {
			if (child.offset() >= offset + length) {
				break;
			}

			if (child.offset() + child.size() < offset) {
				continue;
			}

			long position = offset - child.offset();
			position = position > 0 ? position : 0;
			long size = Math.min(
				child.size() - position,
				length - bytes);
			selected.add(new StorageOffset(child, position, size));
			bytes += size;
		}

		if (selected.size() == 0 || bytes < length) {
			throw new IllegalStateException("Buffer underrun (only got " +
				bytes + " out of " + length + " byte(s) requested)!");
		}

		return selected;
	}
}
