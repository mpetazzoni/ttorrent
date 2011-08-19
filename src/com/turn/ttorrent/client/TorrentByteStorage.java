/** Copyright (C) 2011 Turn, Inc.
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

package com.turn.ttorrent.client;

import java.util.List;
import java.io.IOException;
import java.nio.ByteBuffer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Torrent data storage.
 *
 * <p>
 * A torrent, regardless of whether it contains multiple files or not, is
 * considered as one linear, contiguous byte array. As such, pieces can spread
 * across multiple files.
 * </p>
 *
 * <p>
 * TorrentByteStorage class provides an abstraction for the Piece class
 * to read and write to the torrent's data without having to care about
 * which file(s) a piece is on.
 * </p>
 *
 * @author dgiffin
 */
public class TorrentByteStorage {

	private static final Logger logger =
		LoggerFactory.getLogger(TorrentByteStorage.class);

    List<TorrentByteStorageFile> files;

    private static class FileOffset {
        public long offset;
        public TorrentByteStorageFile file;

        public FileOffset(TorrentByteStorageFile file, long offset) {
            this.file = file;
            this.offset = offset;
        }
    }

	public TorrentByteStorage(List<TorrentByteStorageFile> files) {
		this.files = files;
	}

	public ByteBuffer read(long offset, int length) throws IOException {
        FileOffset fileOffset = select(offset);
        return fileOffset.file.read(fileOffset.offset, length);
	}

	public void write(ByteBuffer block, long offset) throws IOException {
        FileOffset fileOffset = select(offset);
        fileOffset.file.write(block, fileOffset.offset);
	}

    // select file, and calculate position into file
    private FileOffset select(long position) throws IOException {
        long total = 0L;
        for (TorrentByteStorageFile file : files) {
            // logger.debug("checking file {} compare ({} <= {}) && ({} < {})",
            //    new Object[] { file, total, position, position, (total + file.getSize()) });
            if (total <= position && position < (total + file.getSize())) {
                long offset = position - total;
                // logger.debug("found at file {} offset {}", file, offset);
                return new FileOffset(file, offset);
            }
            total += file.getSize();
        }
        throw new IOException(String.format(
                        "position %s past total length %s of all files",
                        position, total
                    ));
    }

	public boolean isFinished() {
        for (TorrentByteStorageFile file : files) {
            if (file.isFinished() == false) return false;
        }
        return true;
	}

	/** Move the partial file to its final location.
	 *
	 * <p>
	 * This method needs to make sure reads can still happen seemlessly during
	 * the operation. The partial is first flushed to the storage device before
	 * being copied to its target location. The {@link FileChannel} is then
	 * switched to this new file before the partial is removed.
	 * </p>
	 */
	public synchronized void finish() throws IOException {
        for (TorrentByteStorageFile file : files) {
            file.finish();
        }
	}

	public synchronized void close() throws IOException {
        for (TorrentByteStorageFile file : files) {
            file.close();
        }
	}
}
