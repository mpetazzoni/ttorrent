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
import java.io.Closeable;
import java.io.Flushable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;
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
 */
public class FileCollectionStorage implements ByteStorage {

    private static final Logger LOG = LoggerFactory.getLogger(FileCollectionStorage.class);
    private final List<? extends ByteRangeStorage> files;

    /**
     * Initialize a new multi-file torrent byte storage.
     *
     * @param files The list of individual {@link ByteRangeStorage}
     * objects making up the torrent.
     * @param size The total size of the torrent data, in bytes.
     */
    public FileCollectionStorage(@Nonnull List<? extends ByteRangeStorage> files) {
        this.files = files;

        LOG.info("Initialized torrent byte storage on {} file(s) "
                + "({} total byte(s)).", files.size(), size());
    }

    public long size() {
        long size = 0;
        for (ByteRangeStorage part : files)
            size += part.size();
        return size;
    }

    @Override
    public int read(ByteBuffer buffer, long offset) throws IOException {
        int requested = buffer.remaining();
        int bytes = 0;

        for (Fragment fo : this.select(offset, requested)) {
            // TODO: remove cast to int when large ByteBuffer support is
            // implemented in Java.
            buffer.limit((int) (bytes + fo.length));
            bytes += fo.part.read(buffer, fo.offset);
        }

        if (bytes < requested) {
            throw new IOException("Storage collection read underrun!");
        }

        return bytes;
    }

    @Override
    public int write(ByteBuffer buffer, long offset) throws IOException {
        int requested = buffer.remaining();
        if (requested <= 0)
            throw new IllegalArgumentException("Suspicious write length " + requested);

        int bytes = 0;

        for (Fragment fo : this.select(offset, requested)) {
            buffer.limit(bytes + (int) fo.length);
            bytes += fo.part.write(buffer, fo.offset);
        }

        if (bytes < requested) {
            throw new IOException("Storage collection write underrun!");
        }

        return bytes;
    }

    @Override
    public void flush() throws IOException {
        for (ByteRangeStorage part : files)
            part.flush();
    }

    @Override
    public void close() throws IOException {
        for (Closeable part : this.files)
            part.close();
    }

    @Override
    public void finish() throws IOException {
        for (ByteStorage part : this.files)
            part.finish();
    }

    @Override
    public boolean isFinished() {
        for (ByteStorage part : this.files)
            if (!part.isFinished())
                return false;
        return true;
    }

    /**
     * File operation details holder.
     *
     * <p>
     * This simple inner class holds the details for a read or write operation
     * on one of the underlying {@link FileStorage}s.
     * </p>
     *
     * @author dgiffin
     * @author mpetazzoni
     */
    private static class Fragment {

        public final ByteStorage part;
        public final long offset;
        public final long length;

        Fragment(ByteStorage part, long offset, long length) {
            this.part = part;
            this.offset = offset;
            this.length = length;
        }

        @Override
        public String toString() {
            return Objects.toStringHelper(this)
                    .add("part", part)
                    .add("offset", offset)
                    .add("length", length)
                    .toString();
        }
    }

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
     * @return A list of {@link FileOffset} objects representing the {@link
     * FileStorage}s impacted by the operation, bundled with their
     * respective relative offset and number of bytes to read or write.
     * @throws IllegalArgumentException If the offset and length go over the
     * byte storage size.
     * @throws IllegalStateException If the files registered with this byte
     * storage can't accommodate the request (should not happen, really).
     */
    @Nonnull
    private List<Fragment> select(@Nonnegative long offset, @Nonnegative long length) {
        if (offset + length > size()) {
            throw new IllegalArgumentException("Buffer overrun ("
                    + offset + " + " + length + " > " + size() + ") !");
        }

        List<Fragment> selected = new ArrayList<Fragment>();
        long bytes = 0;

        for (ByteRangeStorage part : this.files) {
            // Our IO ends after this ByteRangeStorage.
            if (part.offset() >= offset + length) {
                break;
            }

            // Our IO starts before this ByteRangeStorage.
            if (part.offset() + part.size() <= offset) {
                continue;
            }

            long position = offset - part.offset();
            position = position > 0 ? position : 0;
            long size = Math.min(
                    part.size() - position,
                    length - bytes);
            selected.add(new Fragment(part, position, size));
            bytes += size;
        }

        if (selected.isEmpty() || bytes < length) {
            throw new IllegalStateException("Buffer underrun (only got "
                    + bytes + " out of " + length + " byte(s) requested)!");
        }

        return selected;
    }
}
