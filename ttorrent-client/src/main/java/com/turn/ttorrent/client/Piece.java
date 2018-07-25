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
package com.turn.ttorrent.client;

import com.turn.ttorrent.client.peer.SharingPeer;
import com.turn.ttorrent.client.storage.PieceStorage;
import com.turn.ttorrent.common.TorrentLoggerFactory;
import com.turn.ttorrent.common.TorrentUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;


/**
 * A torrent piece.
 *
 * <p>
 * This class represents a torrent piece. Torrents are made of pieces, which
 * are in turn made of blocks that are exchanged using the peer protocol.
 * The piece length is defined at the torrent level, but the last piece that
 * makes the torrent might be smaller.
 * </p>
 *
 * <p>
 * If the torrent has multiple files, pieces can spread across file boundaries.
 * The TorrentByteStorage abstracts this problem to give Piece objects the
 * impression of a contiguous, linear byte storage.
 * </p>
 *
 * @author mpetazzoni
 */
public class Piece implements Comparable<Piece>, PieceInformation {

  private static final Logger logger =
          TorrentLoggerFactory.getLogger();

  private final PieceStorage pieceStorage;
  private final int index;
  private final long length;
  private final byte[] hash;

  private volatile boolean valid;
  private int seen;
  private ByteBuffer data;

  /**
   * Initialize a new piece in the byte bucket.
   *
   * @param pieceStorage  The underlying piece storage bucket.
   * @param index   This piece index in the torrent.
   * @param length  This piece length, in bytes.
   * @param hash    This piece 20-byte SHA1 hash sum.
   */
  public Piece(PieceStorage pieceStorage, int index, long length, byte[] hash) {
    this.pieceStorage = pieceStorage;
    this.index = index;
    this.length = length;
    this.hash = hash;

    // Piece is considered invalid until first check.
    this.valid = false;

    // Piece start unseen
    this.seen = 0;

    this.data = null;
  }

  @Override
  public int getSize() {
    return (int)length;
  }

  /**
   * Tells whether this piece's data is valid or not.
   */
  public boolean isValid() {
    return this.valid;
  }

  /**
   * Returns the index of this piece in the torrent.
   */
  public int getIndex() {
    return this.index;
  }

  /**
   * Returns the size, in bytes, of this piece.
   *
   * <p>
   * All pieces, except the last one, are expected to have the same size.
   * </p>
   */
  public long size() {
    return this.length;
  }

  /**
   * Tells whether this piece is available in the current connected peer swarm.
   */
  public boolean available() {
    return this.seen > 0;
  }

  /**
   * Mark this piece as being seen at the given peer.
   *
   * @param peer The sharing peer this piece has been seen available at.
   */
  public void seenAt(SharingPeer peer) {
    this.seen++;
  }

  /**
   * Mark this piece as no longer being available at the given peer.
   *
   * @param peer The sharing peer from which the piece is no longer available.
   */
  public void noLongerAt(SharingPeer peer) {
    this.seen--;
  }

  void setValid(boolean valid) {
    this.valid = valid;
  }

  /**
   * Validates this piece.
   *
   * @return Returns true if this piece, as stored in the underlying byte
   * storage, is valid, i.e. its SHA1 sum matches the one from the torrent
   * meta-info.
   */
  public boolean validate(SharedTorrent torrent, Piece piece) throws IOException {

    logger.trace("Validating {}...", this);

    // TODO: remove cast to int when large ByteBuffer support is
    // implemented in Java.
    byte[] pieceBytes = data.array();
    final byte[] calculatedHash = TorrentUtils.calculateSha1Hash(pieceBytes);
    this.valid = Arrays.equals(calculatedHash, this.hash);
    logger.trace("validating result of piece {} is {}", this.index, this.valid);

    return this.isValid();
  }

  /**
   * Internal piece data read function.
   *
   * <p>
   * This function will read the piece data without checking if the piece has
   * been validated. It is simply meant at factoring-in the common read code
   * from the validate and read functions.
   * </p>
   *
   * @param offset Offset inside this piece where to start reading.
   * @param length Number of bytes to read from the piece.
   * @return A byte buffer containing the piece data.
   * @throws IllegalArgumentException If <em>offset + length</em> goes over
   *                                  the piece boundary.
   * @throws IOException              If the read can't be completed (I/O error, or EOF
   *                                  reached, which can happen if the piece is not complete).
   */
  private ByteBuffer _read(long offset, long length, ByteBuffer buffer) throws IOException {
    if (offset + length > this.length) {
      throw new IllegalArgumentException("Piece#" + this.index +
              " overrun (" + offset + " + " + length + " > " +
              this.length + ") !");
    }

    // TODO: remove cast to int when large ByteBuffer support is
    // implemented in Java.
    int position = buffer.position();
    byte[] bytes = this.pieceStorage.readPiecePart(this.index, (int)offset, (int)length);
    buffer.put(bytes);
    buffer.rewind();
    buffer.limit(bytes.length + position);
    return buffer;
  }

  /**
   * Read a piece block from the underlying byte storage.
   *
   * <p>
   * This is the public method for reading this piece's data, and it will
   * only succeed if the piece is complete and valid on disk, thus ensuring
   * any data that comes out of this function is valid piece data we can send
   * to other peers.
   * </p>
   *
   * @param offset Offset inside this piece where to start reading.
   * @param length Number of bytes to read from the piece.
   * @return A byte buffer containing the piece data.
   * @throws IllegalArgumentException If <em>offset + length</em> goes over
   *                                  the piece boundary.
   * @throws IllegalStateException    If the piece is not valid when attempting
   *                                  to read it.
   * @throws IOException              If the read can't be completed (I/O error, or EOF
   *                                  reached, which can happen if the piece is not complete).
   */
  public ByteBuffer read(long offset, int length, ByteBuffer block)
          throws IllegalArgumentException, IllegalStateException, IOException {
    if (!this.valid) {
      throw new IllegalStateException("Attempting to read an " +
              "known-to-be invalid piece!");
    }

    return this._read(offset, length, block);
  }

  /**
   * Record the given block at the given offset in this piece.
   *
   * @param block  The ByteBuffer containing the block data.
   * @param offset The block offset in this piece.
   */
  public void record(ByteBuffer block, int offset) {
    if (this.data == null) {
      // TODO: remove cast to int when large ByteBuffer support is
      // implemented in Java.
      this.data = ByteBuffer.allocate((int) this.length);
    }

    int pos = block.position();
    this.data.position(offset);
    this.data.put(block);
    block.position(pos);
  }

  public void finish() throws IOException {
    this.data.rewind();
    logger.trace("Recording {}...", this);
    try {
      pieceStorage.savePiece(index, this.data.array());
    } finally {
      this.data = null;
    }
  }

  /**
   * Return a human-readable representation of this piece.
   */
  public String toString() {
    return String.format("piece#%4d%s",
            this.index,
            this.isValid() ? "+" : "-");
  }

  @Override
  public boolean equals(Object obj) {
    if (obj instanceof Piece) {
      return this.index == ((Piece) obj).index;
    } else {
      return false;
    }
  }

  /**
   * Piece comparison function for ordering pieces based on their
   * availability.
   *
   * @param other The piece to compare with, should not be <em>null</em>.
   */
  public int compareTo(Piece other) {
    // return true for the same pieces, otherwise sort by time seen, then by index;
    if (this.equals(other)) {
      return 0;
    } else if (this.seen == other.seen) {
      return new Integer(this.index).compareTo(other.index);
    } else if (this.seen < other.seen) {
      return -1;
    } else {
      return 1;
    }
  }

}
