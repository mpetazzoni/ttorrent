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

import com.turn.ttorrent.common.Torrent;
import com.turn.ttorrent.client.peer.SharingPeer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A torrent piece.
 *
 * This class represents a torrent piece. Torrents are made of pieces, which
 * are in turn made of blocks that are exchanged using the peer protocol.
 *
 * The piece length is defined at the torrent level, but the last piece that
 * makes the torrent might be smaller.
 *
 * If the torrent has multiple files, pieces can spread across file boundaries.
 * The TorrentByteStorage abstracts this problem to give Piece objects the
 * impression of a contiguous, linear byte storage.
 *
 * @author mpetazzoni
 */
public class Piece implements Comparable<Piece> {

	private static final Logger logger = LoggerFactory.getLogger(Piece.class);

	private TorrentByteStorage bucket;
	private int index;
	private long offset; // support > 2 GB files
	private int length;

	private byte[] hash;
	private boolean valid;
	private boolean seeder;

	private int seen;

	private ByteBuffer data;

	/** Initialize a new piece in the byte bucket.
	 *
	 * @param bucket The underlying byte storage bucket.
	 * @param index This piece index in the torrent.
	 * @param offset This piece offset, in bytes, in the storage.
	 * @param length This piece length, in bytes.
	 * @param hash This piece 20-byte SHA1 hash sum.
	 */
	public Piece(TorrentByteStorage bucket, int index, long offset, int length,
			byte[] hash, boolean seeder) {
		this.bucket = bucket;
		this.index = index;
		this.offset = offset;
		this.length = length;
		this.hash = hash;

		// Piece is considered invalid until first check.
		// unless we are the seeder
		this.valid = false;

		// Are we seeder for this piece?
		this.seeder = seeder;

		// Piece start unseen
		this.seen = 0;

		this.data = null;
	}

	public boolean isSeeder() {
		return this.seeder;
	}

	public boolean isValid() {
		return this.valid;
	}

	public int getIndex() {
		return this.index;
	}

	public int size() {
		return this.length;
	}

	public boolean available() {
		return this.seen > 0;
	}

	public void seenAt(SharingPeer peer) {
		this.seen++;
	}

	public void noLongerAt(SharingPeer peer) {
		this.seen--;
	}

	/** Validates this piece.
	 *
	 * Tells whether this piece, as stored in the underlying byte storage, is
	 * valid, i.e. its SHA1 sum matches the one from the torrent meta-info.
	 */
	public boolean validate() throws IOException {
		this.valid = false;

		if (this.isSeeder()) {
			this.valid = true;
			logger.trace("Validating piece#" + this.index + "... (seeding)");
			return this.isValid();
		}

		try {
			logger.trace("Validating piece#" + this.index + "...");
			ByteBuffer buffer = this.bucket.read(this.offset, this.length);
			if (buffer.remaining() == this.length) {
				byte[] data = new byte[this.length];
				buffer.get(data);
				this.valid = Arrays.equals(Torrent.hash(data), this.hash);
			}
		} catch (NoSuchAlgorithmException nsae) {
			logger.error("{}", nsae);
		}

		return this.isValid();
	}

	/** Read a piece block from the underlying byte storage.
	 *
	 * This is the public method for reading this piece's data, and it will
	 * only succeed if the piece is complete and valid on disk, thus ensuring
	 * any data that comes out of this function is valid piece data we can send
	 * to other peers.
	 *
	 * @param offset Offset inside this piece where to start reading.
	 * @param length Number of bytes to read from the piece.
	 * @return A byte array containing the piece data if the read was
	 * successful.
	 * @throws IllegalArgumentException If <em>offset + length</em> goes over
	 * the piece boundary.
	 * @throws IllegalStateException If the piece is not valid when attempting
	 * to read it.
	 * @throws IOException If the read can't be completed (I/O error, or EOF
	 * reached, which can happen if the piece is not complete).
	 */
	public ByteBuffer read(int offset, int length)
		throws IllegalArgumentException, IllegalStateException, IOException {
		if (!this.valid) {
			throw new IllegalStateException("Attempting to read an " +
					"known-to-be invalid piece!");
		}

		if (offset + length > this.length) {
			throw new IllegalArgumentException("Offset + length goes " +
					"pass the piece size!");
		}

		return this.bucket.read(this.offset + offset, length);
	}

	/** Record the given block at the given offset in this piece.
	 *
	 * <b>Note:</b> this has synchronized access to the underlying byte storage.
	 *
	 * @param block The ByteBuffer containing the block data.
	 * @param offset The block offset in this piece.
	 */
	public synchronized void record(ByteBuffer block, int offset)
		throws IOException {
		if (this.data == null || offset == 0) {
			this.data = ByteBuffer.allocate(this.length);
		}

		int pos = block.position();
		this.data.position(offset);
		this.data.put(block);
		block.position(pos);

		if (block.remaining() + offset == this.length) {
			this.data.rewind();
			logger.trace("Recording " + this + "...");
			this.bucket.write(this.data, this.offset, this.length);
			this.data = null;
		}
	}

	/** Return a human-readable representation of this piece.
	 */
	public String toString() {
		return "piece" + (this.valid ? "" : "!") + "#" + this.index;
	}

	public int compareTo(Piece other) {
		if (this == other) {
			return 0;
		}

		if (this.seen < other.seen) {
			return -1;
		} else {
			return 1;
		}
	}
}
