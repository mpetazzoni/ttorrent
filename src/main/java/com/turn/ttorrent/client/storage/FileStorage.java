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

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

import javax.annotation.Nonnegative;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Single-file torrent byte data storage.
 *
 * <p>
 * This implementation of TorrentByteStorageFile provides a torrent byte data
 * storage relying on a single underlying file and uses a RandomAccessFile
 * FileChannel to expose thread-safe read/write methods.
 * </p>
 *
 * @author mpetazzoni
 */
public class FileStorage implements TorrentByteStorage {

    private static final Logger logger =
            LoggerFactory.getLogger(FileStorage.class);
    private final File target;
    private final long offset;
    private final long size;
    private RandomAccessFile raf;
    private FileChannel channel;
    private File current;

    public FileStorage(File file, long size) throws IOException {
        this(file, 0, size);
    }

    public FileStorage(File file, long offset, long size)
            throws IOException {
        this.target = file;
        this.offset = offset;
        this.size = size;

        File partial = new File(this.target.getAbsolutePath()
                + TorrentByteStorage.PARTIAL_FILE_NAME_SUFFIX);

        if (partial.exists()) {
            logger.debug("Partial download found at {}. Continuing...",
                    partial.getAbsolutePath());
            this.current = partial;
        } else if (!this.target.exists()) {
            logger.debug("Downloading new file to {}...",
                    partial.getAbsolutePath());
            this.current = partial;
        } else {
            logger.debug("Using existing file {}.",
                    this.target.getAbsolutePath());
            this.current = this.target;
        }

        this.raf = new RandomAccessFile(this.current, "rw");

        // Set the file length to the appropriate size, eventually truncating
        // or extending the file if it already exists with a different size.
        this.raf.setLength(this.size);

        this.channel = raf.getChannel();
        logger.info("Initialized byte storage file at {} ({}+{} byte(s)).",
                new Object[]{
            this.current.getAbsolutePath(),
            this.offset,
            this.size,});
    }

    @Nonnegative
    protected long offset() {
        return this.offset;
    }

    @Nonnegative
    public long size() {
        return this.size;
    }

    @Override
    public int read(ByteBuffer buffer, long offset) throws IOException {
        int length = buffer.remaining();
        if (offset + length > this.size)
            throw new IllegalArgumentException("Invalid storage read request: offset=" + offset + ", length=" + length + " when size=" + this.size);

        int read = this.channel.read(buffer, offset);
        if (read < length)
            throw new IOException("Storage underrun: offset=" + offset + ", length=" + length + ", size=" + size + ", read=" + read);

        return read;
    }

    @Override
    public int write(ByteBuffer buffer, long offset) throws IOException {
        int length = buffer.remaining();
        if (offset + length > this.size)
            throw new IllegalArgumentException("Invalid storage write request: offset=" + offset + ", length=" + length + " when size=" + this.size);

        return this.channel.write(buffer, offset);
    }

    @Override
    public synchronized void close() throws IOException {
        logger.debug("Closing file channel to {}...", this.current.getName());
        if (this.channel.isOpen())
            this.channel.force(true);
        this.raf.close();
    }

    /**
     * Move the partial file to its final location.
     */
    @Override
    public synchronized void finish() throws IOException {
        logger.debug("Closing file channel to {} (download complete).", this.current.getName());
        if (this.channel.isOpen())
            this.channel.force(true);

        // Nothing more to do if we're already on the target file.
        if (this.isFinished())
            return;

        this.raf.close();
        FileUtils.deleteQuietly(this.target);
        FileUtils.moveFile(this.current, this.target);
        logger.info("Moved torrent data from {} to {}.",
                this.current.getName(),
                this.target.getName());
        this.current = this.target;

        logger.debug("Re-opening torrent byte storage at {}.",
                this.target.getAbsolutePath());

        this.raf = new RandomAccessFile(this.target, "rw");
        this.raf.setLength(this.size);
        this.channel = this.raf.getChannel();
    }

    @Override
    public boolean isFinished() {
        return this.current.equals(this.target);
    }
}
