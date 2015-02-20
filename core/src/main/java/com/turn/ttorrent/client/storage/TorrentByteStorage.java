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


/**
 * Abstract torrent byte storage.
 *
 * <p>
 * This interface defines the methods for accessing an abstracted torrent byte
 * storage. A torrent, especially when it contains multiple files, needs to be
 * seen as one single continuous stream of bytes. Torrent pieces will most
 * likely span accross file boundaries. This abstracted byte storage aims at
 * providing a simple interface for read/write access to the torrent data,
 * regardless of how it is composed underneath the piece structure.
 * </p>
 *
 * @author mpetazzoni
 * @author dgiffin
 */
public interface TorrentByteStorage {

	public static final String PARTIAL_FILE_NAME_SUFFIX = ".part";

	/**
	 * Returns the total size of the torrent storage.
	 */
	public long size();

	/**
	 * Read from the byte storage.
	 *
	 * <p>
	 * Read {@code length} bytes at offset {@code offset} from the underlying
	 * byte storage and return them in a {@link ByteBuffer}.
	 * </p>
	 *
	 * @param buffer The buffer to read the bytes into. The buffer's limit will
	 * control how many bytes are read from the storage.
	 * @param offset The offset, in bytes, to read from. This must be within
	 * the storage boundary.
	 * @return The number of bytes read from the storage.
	 * @throws IOException If an I/O error occurs while reading from the
	 * byte storage.
	 */
	public int read(ByteBuffer buffer, long offset) throws IOException;

	/**
	 * Write bytes to the byte storage.
	 *
	 * <p>
	 * </p>
	 *
	 * @param block A {@link ByteBuffer} containing the bytes to write to the
	 * storage. The buffer limit is expected to be set correctly: all bytes
	 * from the buffer will be used.
	 * @param offset Offset in the underlying byte storage to write the block
	 * at.
	 * @return The number of bytes written to the storage.
	 * @throws IOException If an I/O error occurs while writing to the byte
	 * storage.
	 */
	public int write(ByteBuffer block, long offset) throws IOException;

	/**
	 * Close this byte storage.
	 *
	 * @throws IOException If closing the underlying storage (file(s) ?)
	 * failed.
	 */
	public void close() throws IOException;

	/**
	 * Finalize the byte storage when the download is complete.
	 *
	 * <p>
	 * This gives the byte storage the opportunity to perform finalization
	 * operations when the download completes, like moving the files from a
	 * temporary location to their destination.
	 * </p>
	 *
	 * @throws IOException If the finalization failed.
	 */
	public void finish() throws IOException;

	/**
	 * Tells whether this byte storage has been finalized.
	 */
	public boolean isFinished();
}
