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
import java.util.LinkedList;
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
        public long offset;  // position into the file
        public int position; // position into the buffer
        public long length;  // bytes to write / read
        public TorrentByteStorageFile file;

        public FileOffset(TorrentByteStorageFile file, long offset, int position, long length) {
            this.file = file;
            this.offset = offset;
            this.position = position;
            this.length = length;
        }
    }

    public TorrentByteStorage(List<TorrentByteStorageFile> files) {
        this.files = files;
    }

    public ByteBuffer read(long offset, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length);
        List<FileOffset> fileOffsets = select(offset, length);
        int total = 0;
        for (FileOffset fileOffset : fileOffsets) {
            total += fileOffset.file.read(buffer, fileOffset.offset);
        }
        buffer.clear();
        buffer.limit(total >= 0 ? total : 0);
        return buffer;
    }

    public void write(ByteBuffer block, long offset, int length) throws IOException {
        List<FileOffset> fileOffsets = select(offset, length);
        if (fileOffsets.size() == 1) {
            // write the whole thing.
            FileOffset fileOffset = fileOffsets.get(0);
            fileOffset.file.write(block, fileOffset.offset);
            return;
        }
        // get all the bytes
        byte[] bytes = block.array();
        for (FileOffset fileOffset : fileOffsets) {
            // create a smaller buffers to write
            ByteBuffer data = ByteBuffer.allocate(new Long(fileOffset.length).intValue()); // too short?
            // put the only the data for this file
            data.put(bytes, fileOffset.position, new Long(fileOffset.length).intValue());
            data.rewind();
            fileOffset.file.write(data, fileOffset.offset);
        }

    }

    // select file, and calculate position into file
    private List<FileOffset> select(long position, int length) throws IOException {

        List<FileOffset> storeFiles = new LinkedList<FileOffset>();

        int nbytes = 0;  // number of bytes / offset into buffer
        long total = 0L; // total offset into the contiguous file 
        boolean found = false;

        for (TorrentByteStorageFile file : files) {
            // logger.debug("checking file {} compare ({} <= {}) && ({} < {})",
            //    new Object[] { file, total, position, position, (total + file.getSize()) });
            if (found || (!found && total <= position && position < (total + file.getSize()))) {
                long offset = position - total;
                long len = file.getSize() - offset;
                if (len > (length - nbytes)) len = length - nbytes; // don't overrun the buffer

                storeFiles.add(new FileOffset(file, offset, nbytes, len));
                if (!found)  logger.trace("found at file {} offset {} length {}", new Object[] {file, offset, len});
                if (found) logger.trace(" another file {} offset {} length {}", new Object[] {file, offset, len});

                // move forward
                nbytes += len; 
                position += len;
                found = true;
            }
            if (nbytes >= length) break; // got enough files already
            total += file.getSize();
        }
        if (storeFiles.size() == 0) {
            throw new IOException(String.format(
                        "position %s past total length %s of all files",
                        position, total
                    ));
        }
        return storeFiles;
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
