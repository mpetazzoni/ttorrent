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

import com.turn.ttorrent.common.Torrent;
import com.turn.ttorrent.client.peer.SharingPeer;
import com.turn.ttorrent.client.storage.TorrentByteStorage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.concurrent.Callable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


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
public class Piece implements Comparable<Piece> {

	private static final Logger logger =
		LoggerFactory.getLogger(Piece.class);

	private final TorrentByteStorage bucket;
	private final int index;
	private final long offset;
	private final long length;
	private final byte[] hash;
	private final boolean seeder;

	private volatile boolean valid;
	private int seen;
	private ByteBuffer data;

	/**
	 * Initialize a new piece in the byte bucket.
	 *
	 * @param bucket The underlying byte storage bucket.
	 * @param index This piece index in the torrent.
	 * @param offset This piece offset, in bytes, in the storage.
	 * @param length This piece length, in bytes.
	 * @param hash This piece 20-byte SHA1 hash sum.
	 * @param seeder Whether we're seeding this torrent or not (disables piece
	 * validation).
	 */
	public Piece(TorrentByteStorage bucket, int index, long offset,
		long length, byte[] hash, boolean seeder) {
		this.bucket = bucket;
		this.index = index;
		this.offset = offset;
		this.length = length;
		this.hash = hash;
		this.seeder = seeder;

		// Piece is considered invalid until first check.
		this.valid = false;

		// Piece start unseen
		this.seen = 0;

		this.data = null;
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

	/**
	 * Validates this piece.
	 *
	 * @return Returns true if this piece, as stored in the underlying byte
	 * storage, is valid, i.e. its SHA1 sum matches the one from the torrent
	 * meta-info.
	 */
	public synchronized boolean validate() throws IOException {
		if (this.seeder) {
			logger.trace("Skipping validation of {} (seeder mode).", this);
			this.valid = true;
			return true;
		}

		logger.trace("Validating {}...", this);
		this.valid = false;

		ByteBuffer buffer = this._read(0, this.length);
		byte[] data = new byte[(int)this.length];
		buffer.get(data);
		try {
			this.valid = Arrays.equals(Torrent.hash(data), this.hash);
		} catch (NoSuchAlgorithmException e) {
			this.valid = false;
		}

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
	 * the piece boundary.
	 * @throws IOException If the read can't be completed (I/O error, or EOF
	 * reached, which can happen if the piece is not complete).
	 */
	private ByteBuffer _read(long offset, long length) throws IOException {
		if (offset + length > this.length) {
			throw new IllegalArgumentException("Piece#" + this.index +
				" overrun (" + offset + " + " + length + " > " +
				this.length + ") !");
		}

		// TODO: remove cast to int when large ByteBuffer support is
		// implemented in Java.
		ByteBuffer buffer = ByteBuffer.allocate((int)length);
		int bytes = this.bucket.read(buffer, this.offset + offset);
		buffer.rewind();
		buffer.limit(bytes >= 0 ? bytes : 0);
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
	 * the piece boundary.
	 * @throws IllegalStateException If the piece is not valid when attempting
	 * to read it.
	 * @throws IOException If the read can't be completed (I/O error, or EOF
	 * reached, which can happen if the piece is not complete).
	 */
	public ByteBuffer read(long offset, int length)
		throws IllegalArgumentException, IllegalStateException, IOException {
		if (!this.valid) {
			throw new IllegalStateException("Attempting to read an " +
					"known-to-be invalid piece!");
		}

		return this._read(offset, length);
	}

	/**
	 * Record the given block at the given offset in this piece.
	 *
	 * <p>
	 * <b>Note:</b> this has synchronized access to the underlying byte storage.
	 * </p>
	 *
	 * @param block The ByteBuffer containing the block data.
	 * @param offset The block offset in this piece.
	 */
	public synchronized void record(ByteBuffer block, int offset)
		throws IOException {
		if (this.data == null || offset == 0) {
			// TODO: remove cast to int when large ByteBuffer support is
			// implemented in Java.
			this.data = ByteBuffer.allocate((int)this.length);
		}

		int pos = block.position();
		this.data.position(offset);
		this.data.put(block);
		block.position(pos);

		if (block.remaining() + offset == this.length) {
			this.data.rewind();
			logger.trace("Recording {}...", this);
			this.bucket.write(this.data, this.offset);
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

	/**
	 * Piece comparison function for ordering pieces based on their
	 * availability.
	 *
	 * @param other The piece to compare with, should not be <em>null</em>.
	 */
	public int compareTo(Piece other) {
		if (this.seen != other.seen) {
			return this.seen < other.seen ? -1 : 1;
		}
		return this.index == other.index ? 0 :
			(this.index < other.index ? -1 : 1);
	}

	/**
	 * A {@link Callable} to call the piece validation function.
	 *
	 * <p>
	 * This {@link Callable} implementation allows for the calling of the piece
	 * validation function in a controlled context like a thread or an
	 * executor. It returns the piece it was created for. Results of the
	 * validation can easily be extracted from the {@link Piece} object after
	 * it is returned.
	 * </p>
	 *
	 * @author mpetazzoni
	 */
	public static class CallableHasher implements Callable<Piece> {

		private final Piece piece;

		public CallableHasher(Piece piece) {
			this.piece = piece;
		}

		@Override
		public Piece call() throws IOException {
			this.piece.validate();
			return this.piece;
		}
	}
}
