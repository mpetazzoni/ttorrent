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

import com.google.common.base.Objects;
import com.turn.ttorrent.protocol.TorrentUtils;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

import java.nio.file.StandardOpenOption;
import java.util.EnumSet;
import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;
import javax.annotation.concurrent.GuardedBy;
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
public class FileStorage implements ByteRangeStorage {

    private static final Logger LOG = LoggerFactory.getLogger(FileStorage.class);
    private final File target;
    private final long offset;
    private final long size;
    @GuardedBy("lock")
    private FileChannel channel;
    @GuardedBy("lock")
    private File current;
    @GuardedBy("lock")
    private boolean finished;
    private final Object lock = new Object();

    public FileStorage(@Nonnull File file, @Nonnegative long size) throws IOException {
        this(file, 0, size);
    }

    public FileStorage(@Nonnull File file, @Nonnegative long offset, @Nonnegative long size)
            throws IOException {
        this.target = file;
        this.offset = offset;
        this.size = size;

        File partial = new File(file.getAbsolutePath()
                + ByteStorage.PARTIAL_FILE_NAME_SUFFIX);

        if (partial.exists()) {
            LOG.debug("{}: Partial download found at {}. Continuing...",
                    target.getAbsolutePath(), partial.getAbsolutePath());
            this.current = partial;
        } else if (!this.target.exists()) {
            LOG.debug("{}: Downloading new file to {}...",
                    target.getAbsolutePath(), partial.getAbsolutePath());
            this.current = partial;
        } else {
            LOG.debug("{}: Using existing file.",
                    target.getAbsolutePath(), target.getAbsolutePath());
            this.current = this.target;
        }

        // Non-final variables are not guaranteed written before the end of a constructor.
        synchronized (lock) {
            // Set the file length to the appropriate size, eventually truncating
            // or extending the file if it already exists with a different size.
            RandomAccessFile raf = new RandomAccessFile(current, "rw");
            try {
                raf.setLength(size);
            } finally {
                raf.close();
            }

            this.channel = FileChannel.open(current.toPath(), EnumSet.of(StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE));
            this.finished = false;
        }
        LOG.info("{}: Initialized byte storage file at {} ({}+{} byte(s)).",
                new Object[]{
            target.getAbsolutePath(),
            current.getAbsolutePath(),
            offset, size
        });
    }

    @Nonnull
    public File getFile() {
        return target;
    }

    @Override
    public long offset() {
        return this.offset;
    }

    @Override
    public long size() {
        return this.size;
    }

    @Override
    public int read(ByteBuffer buffer, long offset) throws IOException {
        synchronized (lock) {
            if (channel == null)
                throw new NullPointerException("Channel is null.");

            int length = buffer.remaining();
            if (offset + length > this.size)
                throw new IllegalArgumentException(target.getAbsolutePath() + ": Invalid storage read request: offset=" + offset + ", length=" + length + " when size=" + this.size);

            int read = channel.read(buffer, offset);
            if (read < length)
                throw new IOException(target.getAbsolutePath() + ": Storage underrun: offset=" + offset + ", length=" + length + ", size=" + size + ", read=" + read);

            return read;
        }
    }

    @Override
    public int write(ByteBuffer buffer, long offset) throws IOException {
        synchronized (lock) {
            if (LOG.isTraceEnabled())
                LOG.trace("{}: Write @{}: {} {}", new Object[]{
                    target.getAbsolutePath(),
                    offset, current, TorrentUtils.toString(buffer, 16)
                });
            if (isFinished())
                throw new IllegalStateException("Already finished.");
            if (channel == null)
                throw new NullPointerException("Channel is null.");
            int length = buffer.remaining();
            if (length <= 0)
                throw new IllegalArgumentException(target.getAbsolutePath() + ": Suspicious write length " + length);
            if (offset + length > this.size)
                throw new IllegalArgumentException(target.getAbsolutePath() + ": Invalid storage write request: offset=" + offset + ", length=" + length + " when size=" + this.size);

            return channel.write(buffer, offset);
        }
    }

    @Override
    public void flush() throws IOException {
        synchronized (lock) {
            if (channel != null)
                channel.force(true);
            else
                LOG.warn("{}: Not flushing {}: Not open.", target.getAbsolutePath(), current);
        }
    }

    @Override
    public void close() throws IOException {
        synchronized (lock) {
            if (channel != null) {
                LOG.debug("{}: Closing file channel to {}.", new Object[]{
                    target.getAbsolutePath(), current.getName()
                });
                flush();    // FileChannel does NOT flush on close.
                channel.close();
                channel = null;
            } else {
                LOG.warn("{}: Not closing {}: Not open.", target.getAbsolutePath(), current);
            }
        }
    }

    /**
     * Move the partial file to its final location.
     */
    @Override
    public void finish() throws IOException {
        synchronized (lock) {
            // Nothing more to do if we're already on the target file.
            if (isFinished())
                return;

            close();

            if (!current.equals(target)) {
                FileUtils.deleteQuietly(this.target);
                FileUtils.moveFile(this.current, this.target);
                LOG.info("{}: Moved torrent data from {} to {}.", new Object[]{
                    target.getAbsolutePath(),
                    current.getName(),
                    target.getName()
                });
                current = target;
            }

            if (LOG.isDebugEnabled())
                LOG.debug("{}: Re-opening torrent byte storage.",
                        this.target.getAbsolutePath());

            this.channel = FileChannel.open(target.toPath(), EnumSet.of(StandardOpenOption.READ));
            this.finished = true;
        }
    }

    @Override
    public boolean isFinished() {
        synchronized (lock) {
            // We can't use target.equals(current) because we might be
            // writing to an existing file.
            return finished;
        }
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("file", target)
                .add("offset", offset)
                .add("size", size)
                .toString();
    }
}