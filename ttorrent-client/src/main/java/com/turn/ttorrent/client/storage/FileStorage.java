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

import com.turn.ttorrent.common.TorrentLoggerFactory;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.FileChannel;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;


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

  private static final String PARTIAL_FILE_NAME_SUFFIX = ".part";

  private static final Logger logger =
          TorrentLoggerFactory.getLogger();

  private final File target;
  private File partial;
  private final long offset;
  private final long size;

  private RandomAccessFile raf;
  private FileChannel channel;
  private File current;
  private boolean myIsOpen = false;

  private final ReadWriteLock myLock = new ReentrantReadWriteLock();

  public FileStorage(File file, long offset, long size)
          throws IOException {
    this.target = file;
    this.offset = offset;
    this.size = size;

  }

  public void open(final boolean seeder) throws IOException {
    try {
      myLock.writeLock().lock();
      if (seeder) {
        if (!target.exists()) {
          throw new IOException("Target file " + target.getAbsolutePath() + " doesn't exist.");
        }
        this.current = this.target;
        this.raf = new RandomAccessFile(this.current, "r");
      } else {
        this.partial = new File(this.target.getAbsolutePath() + PARTIAL_FILE_NAME_SUFFIX);

        if (this.partial.exists()) {
          logger.debug("Partial download found at {}. Continuing...",
                  this.partial.getAbsolutePath());
          this.current = this.partial;
        } else if (!this.target.exists()) {
          logger.debug("Downloading new file to {}...",
                  this.partial.getAbsolutePath());
          this.current = this.partial;
        } else {
          logger.debug("Using existing file {}.",
                  this.target.getAbsolutePath());
          this.current = this.target;
        }
        this.raf = new RandomAccessFile(this.current, "rw");
        this.raf.setLength(this.size);
      }

      // Set the file length to the appropriate size, eventually truncating
      // or extending the file if it already exists with a different size.
      myIsOpen = true;
      this.channel = raf.getChannel();

      logger.debug("Opened byte storage file at {} ({}+{} byte(s)).",
              new Object[]{
                      this.current.getAbsolutePath(),
                      this.offset,
                      this.size,
              });
    } finally {
      myLock.writeLock().unlock();
    }
  }

  protected long offset() {
    return this.offset;
  }

  public long size() {
    return this.size;
  }

  @Override
  public int read(ByteBuffer buffer, long position) throws IOException {
    try {
      myLock.readLock().lock();
      int requested = buffer.remaining();

      if (position + requested > this.size) {
        throw new IllegalArgumentException("Invalid storage read request!");
      }

      int bytes = this.channel.read(buffer, position);
      if (bytes < requested) {
        throw new IOException("Storage underrun!");
      }

      return bytes;
    } finally {
      myLock.readLock().unlock();
    }
  }

  @Override
  public int write(ByteBuffer buffer, long position) throws IOException {
    try {
      myLock.writeLock().lock();
      int requested = buffer.remaining();

      if (position + requested > this.size) {
        throw new IllegalArgumentException("Invalid storage write request!");
      }

      return this.channel.write(buffer, position);
    } finally {
      myLock.writeLock().unlock();
    }
  }

  @Override
  public void close() throws IOException {
    try {
      myLock.writeLock().lock();
      if (!myIsOpen) return;
      logger.debug("Closing file channel to {}. Channel open: {}", current.getName(), channel.isOpen());
      if (this.channel.isOpen()) {
        try {
          this.channel.force(true);
        } catch (ClosedByInterruptException ignored) {
        }
      }
      this.raf.close();
      myIsOpen = false;
    } finally {
      myLock.writeLock().unlock();
    }
  }

  /**
   * Move the partial file to its final location.
   */
  @Override
  public void finish() throws IOException {
    try {
      myLock.writeLock().lock();
      logger.debug("Closing file channel to " + this.current.getName() +
              " (download complete).");
      if (this.channel.isOpen()) {
        this.channel.force(true);
      }

      // Nothing more to do if we're already on the target file.
      if (this.isFinished()) {
        return;
      }

      try {
        FileUtils.deleteQuietly(this.target);
        this.raf.close();
        FileUtils.moveFile(this.current, this.target);
      } catch (Exception ex) {
        logger.error("An error occurred while moving file to its final location", ex);
        if (this.target.exists()) {
          throw new IOException("Was unable to delete existing file " + target.getAbsolutePath(), ex);
        }
        FileUtils.copyFile(this.current, this.target);
      }

      this.current = this.target;

      FileUtils.deleteQuietly(this.partial);
      myIsOpen = false;
      logger.debug("Moved torrent data from {} to {}.",
              this.partial.getName(),
              this.target.getName());
    } finally {
      myLock.writeLock().unlock();
    }
  }

  public boolean isOpen() {
    try {
      myLock.readLock().lock();
      return myIsOpen;
    } finally {
      myLock.readLock().unlock();
    }
  }

  @Override
  public boolean isFinished() {
    return this.current.equals(this.target);
  }

  @Override
  public void delete() throws IOException {
    close();
    final File local = this.current;
    if (local != null) local.delete();
  }
}
