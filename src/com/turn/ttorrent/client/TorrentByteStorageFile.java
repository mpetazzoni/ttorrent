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

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

import java.util.List;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Torrent data storage.
 *
 * <p>
 * TorrentByteStorageFile class provides an abstraction for the Piece
 * class to read and write to the torrent's data without having to care about
 * which file(s) a piece is on.
 * </p>
 *
 * <p>
 * The current implementation uses a RandomAccessFile FileChannel to expose
 * thread-safe read/write methods.
 * </p>
 *
 * @author mpetazzoni
 */
public class TorrentByteStorageFile {

    private static final Logger logger =
        LoggerFactory.getLogger(TorrentByteStorageFile.class);

    private static final String PARTIAL_FILE_NAME_SUFFIX = ".part";

    private File target;
    private File partial;
    private File current;

    private RandomAccessFile raf;
    private FileChannel channel;
    private long size;

    public TorrentByteStorageFile(File file, long size) throws IOException {
        this.target = file;
        this.size = size;

        this.partial = new File(this.target.getAbsolutePath() +
            TorrentByteStorageFile.PARTIAL_FILE_NAME_SUFFIX);

        if (this.partial.exists()) {
            logger.info("Partial download found at " +
                this.partial.getAbsolutePath() + ". Continuing...");
            this.current = this.partial;
        } else if (!this.target.exists()) {
            logger.info("Downloading new file to " +
                this.partial.getAbsolutePath() + "...");
            this.current = this.partial;
        } else {
            logger.info("Using existing file " +
                this.target.getAbsolutePath() + ".");
            this.current = this.target;
        }

        this.raf = new RandomAccessFile(this.current, "rw");

        // Set the file length to the appropriate size, eventually truncating
        // or extending the file if it already exists with a different size.
        this.raf.setLength(this.size);

        this.channel = raf.getChannel();
        logger.debug("Initialized torrent byte storage at " +
            this.current.getAbsolutePath() + ".");
    }

    public long getSize() {
        return this.size;
    }

    public int read(ByteBuffer buffer, long offset) throws IOException {
        return this.channel.read(buffer, offset);
    }

    public void write(ByteBuffer block, long offset) throws IOException {
        this.channel.write(block, offset);
    }

    public boolean isFinished() {
        return this.current.equals(this.target);
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
        this.channel.force(true);

        // Nothing more to do if we're already on the target file.
        if (this.isFinished()) {
            return;
        }

        try {
            FileUtils.deleteQuietly(this.target);
            FileUtils.copyFile(this.current, this.target);
        } catch (IOException ioe) {
            // Don't leave a partially copied file in the destination.
            FileUtils.deleteQuietly(this.target);
            throw ioe;
        }

        logger.debug("Re-opening torrent byte storage at " +
                this.target.getAbsolutePath() + ".");

        RandomAccessFile raf = new RandomAccessFile(this.target, "rw");
        raf.setLength(this.size);

        this.channel = raf.getChannel();
        this.raf.close();
        this.raf = raf;
        this.current = this.target;

        FileUtils.deleteQuietly(this.partial);
        logger.info("Moved torrent data from " + this.partial.getName() +
            " to " + this.target.getName() + ".");
    }

    public synchronized void close() throws IOException {
        this.channel.force(true);
        this.raf.close();
    }

    public String toString() {
        return String.format("%s (%s)", target.getName(), size);
    }
}
