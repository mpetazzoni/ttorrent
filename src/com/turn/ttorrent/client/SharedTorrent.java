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

import com.turn.ttorrent.bcodec.InvalidBEncodingException;
import com.turn.ttorrent.common.Torrent;
import com.turn.ttorrent.client.peer.PeerActivityListener;
import com.turn.ttorrent.client.peer.SharingPeer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.Random;
import java.util.SortedSet;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A torrent shared by the BitTorrent client.
 *
 * <p>
 * The {@link SharedTorrent} class extends the Torrent class with all the data
 * and logic required by the BitTorrent client implementation.
 * </p>
 *
 * <p>
 * <em>Note:</em> this implementation currently only supports single-file
 * torrents.
 * </p>
 *
 * @author mpetazzoni
 */
public class SharedTorrent extends Torrent implements PeerActivityListener {

	private static final Logger logger =
		LoggerFactory.getLogger(SharedTorrent.class);

	/** Randomly select the next piece to download from a peer from the
	 * RAREST_PIECE_JITTER available from it. */
	private static final int RAREST_PIECE_JITTER = 42;

	private Random random;
	private boolean stop;

	private long uploaded;
	private long downloaded;
	private long left;

	private final TorrentByteStorage bucket;
	private File file;

	private final int pieceLength;
	private final ByteBuffer piecesHashes;

	private boolean initialized;
	private Piece[] pieces;
	private SortedSet<Piece> rarest;
	private BitSet completedPieces;
	private BitSet requestedPieces;

	/** Create a new shared torrent from a base Torrent object.
	 *
	 * This will recreate a SharedTorrent object from the provided Torrent
	 * object's encoded meta-info data.
	 *
	 * @param torrent The Torrent object.
	 * @param destDir The destination directory or location of the torrent
	 * files.
	 * @throws IllegalArgumentException When the info dictionnary can't be
	 * encoded and hashed back to create the torrent's SHA-1 hash.
	 * @throws FileNotFoundException If the torrent file location or
	 * destination directory does not exist and can't be created.
	 * @throws IOException If the torrent file cannot be accessed.
	 */
	public SharedTorrent(Torrent torrent, File destDir)
		throws IllegalArgumentException, FileNotFoundException, IOException {
		this(torrent, destDir, false);
	}

	/** Create a new shared torrent from a base Torrent object.
	 *
	 * This will recreate a SharedTorrent object from the provided Torrent
	 * object's encoded meta-info data.
	 *
	 * @param torrent The Torrent object.
	 * @param destDir The destination directory or location of the torrent
	 * files.
	 * @param seeder Whether we're a seeder for this torrent or not (disables
	 * validation).
	 * @throws IllegalArgumentException When the info dictionnary can't be
	 * encoded and hashed back to create the torrent's SHA-1 hash.
	 * @throws FileNotFoundException If the torrent file location or
	 * destination directory does not exist and can't be created.
	 * @throws IOException If the torrent file cannot be accessed.
	 */
	public SharedTorrent(Torrent torrent, File destDir, boolean seeder)
		throws IllegalArgumentException, FileNotFoundException, IOException {
		this(torrent.getEncoded(), destDir, seeder);
	}

	/** Create a new shared torrent from metainfo binary data.
	 *
	 * @param torrent The metainfo byte data.
	 * @param destDir The destination directory or location of the torrent
	 * files.
	 * @throws IllegalArgumentException When the info dictionary can't be
	 * encoded and hashed back to create the torrent's SHA-1 hash.
	 * @throws FileNotFoundException If the torrent file location or
	 * destination directory does not exist and can't be created.
	 * @throws IOException If the torrent file cannot be accessed.
	 */
	public SharedTorrent(byte[] torrent, File destDir)
		throws IllegalArgumentException, FileNotFoundException, IOException {
		this(torrent, destDir, false);
	}

	/** Create a new shared torrent from metainfo binary data.
	 *
	 * @param torrent The metainfo byte data.
	 * @param destDir The destination directory or location of the torrent
	 * files.
	 * @param seeder Whether we're a seeder for this torrent or not (disables
	 * validation).
	 * @throws IllegalArgumentException When the info dictionary can't be
	 * encoded and hashed back to create the torrent's SHA-1 hash.
	 * @throws FileNotFoundException If the torrent file location or
	 * destination directory does not exist and can't be created.
	 * @throws IOException If the torrent file cannot be accessed.
	 */
	public SharedTorrent(byte[] torrent, File destDir, boolean seeder)
		throws IllegalArgumentException, FileNotFoundException, IOException {
		super(torrent, seeder);

		// Only deal with single-file torrents for now
		if (this.decoded_info.containsKey("files")) {
			throw new IllegalArgumentException(
					"Multiple files torrents not implemented yet!");
		}

		// Make sure the destination/location directory exists, or try to
		// create it.
		if (!destDir.exists()) {
			if (!destDir.mkdirs()) {
				throw new FileNotFoundException(
						"Invalid destination/location directory!");
			}
		}

		try {
			this.pieceLength = this.decoded_info.get("piece length").getInt();
			this.piecesHashes = ByteBuffer.wrap(this.decoded_info.get("pieces")
					.getBytes());

			if (this.piecesHashes.capacity() / Torrent.PIECE_HASH_SIZE *
					this.pieceLength < this.getSize()) {
				throw new IllegalArgumentException("Torrent size does not " +
						"match the number of pieces and the piece size!");
			}
		} catch (InvalidBEncodingException ibee) {
			throw new IllegalArgumentException(
					"Error reading torrent meta-info fields!");
		}

		this.file = new File(destDir, this.getName());
		this.bucket = new TorrentByteStorage(this.file, this.getSize());

		this.random = new Random(System.currentTimeMillis());
		this.stop = false;

		this.uploaded = 0;
		this.downloaded = 0;
		this.left = this.getSize();

		this.initialized = false;
		this.pieces = new Piece[0];
		this.rarest = Collections.synchronizedSortedSet(new TreeSet<Piece>());
		this.completedPieces = new BitSet();
		this.requestedPieces = new BitSet();
	}

	/** Create a new shared torrent from the given torrent file.
	 *
	 * @param source The <code>.torrent</code> file to read the torrent
	 * meta-info from.
	 * @param destDir The destination directory or location of the torrent
	 * files.
	 * @throws IllegalArgumentException When the info dictionnary can't be
	 * encoded and hashed back to create the torrent's SHA-1 hash.
	 * @throws IOException When the torrent file cannot be read.
	 */
	public static SharedTorrent fromFile(File source, File destDir)
		throws IllegalArgumentException, IOException {
		FileInputStream fis = new FileInputStream(source);
		byte[] data = new byte[(int)source.length()];
		fis.read(data);
		fis.close();
		return new SharedTorrent(data, destDir);
	}

	/** Get the number of bytes uploaded for this torrent.
	 */
	public long getUploaded() {
		return this.uploaded;
	}

	/** Get the number of bytes downloaded for this torrent.
	 *
	 * <b>Note:</b> this could be more than the torrent's length, and should
	 * not be used to determine a completion percentage.
	 */
	public long getDownloaded() {
		return this.downloaded;
	}

	/** Get the number of bytes left to download for this torrent.
	 */
	public long getLeft() {
		return this.left;
	}

	/** Tells whether this torrent has been fully initialized yet.
	 */
	public boolean isInitialized() {
		return this.initialized;
	}

	/** Build this torrent's pieces array.
	 *
	 * <p>
	 * Hash and verify any potentially present local data and create this
	 * torrent's pieces array from their respective hash provided in the
	 * torrent meta-info.
	 * </p>
	 *
	 * <p>
	 * This function should be called soon after the constructor to initialize
	 * the pieces array.
	 * </p>
	 */
	public synchronized void init() {
		if (this.isInitialized()) {
			throw new IllegalStateException("Torrent was already initialized!");
		}

		new Thread("analyze-" +
				this.getHexInfoHash().substring(this.getHexInfoHash().length()-6)) {
			@Override
			public void run() {
				try {
					analyze();
				} catch (InterruptedException ie) {
					logger.warn("{}", ie.getMessage());
				} catch (IOException ioe) {
					logger.warn("Error while analyzing torrent data: {}",
						ioe.getMessage());
				}
			}
		}.start();
	}

	/** Stop the torrent initialization as soon as possible.
	 */
	public void stop() {
		this.stop = true;
	}

	private void analyze() throws InterruptedException, IOException {
		int nPieces = new Double(Math.ceil((double)this.getSize() /
					this.pieceLength)).intValue();
		this.pieces = new Piece[nPieces];
		this.completedPieces = new BitSet(nPieces);

		this.piecesHashes.clear();

		logger.debug("Analyzing local data for {}...", this.getName());
		for (int idx=0; idx<this.pieces.length; idx++) {
			byte[] hash = new byte[Torrent.PIECE_HASH_SIZE];
			this.piecesHashes.get(hash);

			// The last piece may be shorter than the torrent's global piece
			// length.
			int len = (idx < this.pieces.length - 1)
				? this.pieceLength
				: (int)(this.getSize() % this.pieceLength);
			long off = ((long)idx) * this.pieceLength;

			this.pieces[idx] = new Piece(this.bucket, idx, off, len, hash,
				this.isSeeder());
			this.pieces[idx].validate();
			if (this.pieces[idx].isValid()) {
				this.completedPieces.set(idx);
				this.left -= len;
			}

			if (this.stop) {
				throw new InterruptedException("Torrent data analysis " +
					"interrupted at piece " + this.pieces[idx] + "!");
			}
		}

		this.initialized = true;
		logger.debug("{}: {}/{} bytes [{}/{}].",
			new Object[] {
				this.getName(),
				(this.getSize() - this.left),
				this.getSize(),
				this.completedPieces.cardinality(),
				this.pieces.length
			});
	}

	public synchronized void close() {
		try {
			this.bucket.close();
		} catch (IOException ioe) {
			logger.error("Error closing torrent byte storage: {}",
				ioe.getMessage());
		}
	}

	/** Retrieve a piece object by index.
	 *
	 * @param index The index of the piece in this torrent.
	 */
	public Piece getPiece(int index) {
		if (this.pieces == null) {
			throw new IllegalStateException("Torrent not initialized yet.");
		}

		if (index >= this.pieces.length) {
			throw new IllegalArgumentException("Invalid piece index!");
		}

		return this.pieces[index];
	}

	/** Get the number of pieces in this torrent.
	 */
	public int getPieceCount() {
		if (this.pieces == null) {
			throw new IllegalStateException("Torrent not initialized yet.");
		}

		return this.pieces.length;
	}


	/** Return a copy of the bitfield of available pieces for this torrent.
	 *
	 * Available pieces are pieces available in the swarm, and it does not
	 * include our own pieces.
	 */
	public BitSet getAvailablePieces() {
		if (!this.isInitialized()) {
			throw new IllegalStateException("Torrent not yet initialized!");
		}

		BitSet availablePieces = new BitSet(this.pieces.length);

		synchronized (this.pieces) {
			for (Piece piece : this.pieces) {
				if (piece.available()) {
					availablePieces.set(piece.getIndex());
				}
			}
		}

		return availablePieces;
	}

	public BitSet getCompletedPieces() {
		if (!this.isInitialized()) {
			throw new IllegalStateException("Torrent not yet initialized!");
		}

		synchronized (this.completedPieces) {
			return (BitSet)this.completedPieces.clone();
		}
	}

	/** Tells whether this torrent has been fully downloaded, or is fully
	 * available locally.
	 */
	public synchronized boolean isComplete() {
		return this.pieces.length > 0 &&
			this.completedPieces.cardinality() == this.pieces.length;
	}

	/** Finalize the download of this torrent.
	 *
	 * <p>
	 * This realizes the final, pre-seeding phase actions on this torrent,
	 * which usually consists in putting the torrent data in their final form
	 * and at their target location.
	 * </p>
	 *
	 * @see TorrentByteStorage#finish
	 */
	public synchronized void finish() throws IOException {
		if (!this.isInitialized()) {
			throw new IllegalStateException("Torrent not yet initialized!");
		}

		if (!this.isComplete()) {
			throw new IllegalStateException("Torrent download is not complete!");
		}

		this.bucket.finish();
	}

	public synchronized boolean isFinished() {
		return this.isComplete() && this.bucket.isFinished();
	}

	/** Return the completion percentage of this torrent.
	 *
	 * This is computed from the number of completed pieces divided by the
	 * number of pieces in this torrent, times 100.
	 */
	public float getCompletion() {
		return (float)this.completedPieces.cardinality() /
			(float)this.pieces.length * 100.0f;
	}

	/** Mark a piece as completed, decremeting the piece size in bytes from our
	 * left bytes to download counter.
	 */
	public synchronized void markCompleted(Piece piece) {
		if (this.completedPieces.get(piece.getIndex())) {
			return;
		}

		// A completed piece means that's that much data left to download for
		// this torrent.
		this.left -= piece.size();
		this.completedPieces.set(piece.getIndex());
	}

	/** PeerActivityListener handler(s). *************************************/

	/** Peer choked handler.
	 *
	 * When a peer chokes, the requests made to it are canceled and we need to
	 * mark the eventually piece we requested from it as available again for
	 * download tentative from another peer.
	 *
	 * @param peer The peer that choked.
	 */
	@Override
	public synchronized void handlePeerChoked(SharingPeer peer) {
		Piece piece = peer.getRequestedPiece();

		if (piece != null) {
			this.requestedPieces.set(piece.getIndex(), false);
		}

		logger.trace("Peer {} choked, we now have {} outstanding " +
				"request(s): {}.",
			new Object[] {
				peer,
				this.requestedPieces.cardinality(),
				this.requestedPieces
			});
	}

	/** Peer ready handler.
	 *
	 * When a peer becomes ready to accept piece block requests, select a piece
	 * to download and go for it.
	 *
	 * @param peer The peer that became ready.
	 */
	@Override
	public synchronized void handlePeerReady(SharingPeer peer) {
		ArrayList<Piece> choice = new ArrayList<Piece>(
				SharedTorrent.RAREST_PIECE_JITTER);

		BitSet interesting = peer.getAvailablePieces();
		interesting.andNot(this.completedPieces);
		interesting.andNot(this.requestedPieces);

		logger.trace("Peer {} is ready and has {} interesting piece(s).",
			peer, interesting.cardinality());
		logger.trace("Peer has {} piece(s), we have {} piece(s) and {} " +
			"outstanding request(s): {}.",
			new Object[] {
				peer.getAvailablePieces().cardinality(),
				this.completedPieces.cardinality(),
				this.requestedPieces.cardinality(),
				this.requestedPieces
			});

		// Bail out immediately if the peer has no interesting pieces
		if (interesting.cardinality() == 0) {
			return;
		}

		// Extract the RAREST_PIECE_JITTER rarest pieces from the interesting
		// pieces of this peer.
		for (Piece piece : this.rarest) {
			if (interesting.get(piece.getIndex())) {
				choice.add(piece);
				if (choice.size() == RAREST_PIECE_JITTER) {
					break;
				}
			}
		}

		Piece chosen = choice.get(this.random.nextInt(
					Math.min(choice.size(),
						SharedTorrent.RAREST_PIECE_JITTER)));
		this.requestedPieces.set(chosen.getIndex());
		logger.trace("Requesting {} from {}, we now have {} " +
				" outstanding request(s): {}.",
			new Object[] {
				chosen,
				peer,
				this.requestedPieces.cardinality(),
				this.requestedPieces
			});
		peer.downloadPiece(chosen);
	}

	/** Piece availability handler.
	 *
	 * Handle updates in piece availability from a peer's HAVE message. When
	 * this happens, we need to mark that piece as available from the peer.
	 *
	 * @param peer The peer we got the update from.
	 * @param piece The piece that became available.
	 */
	@Override
	public synchronized void handlePieceAvailability(SharingPeer peer,
			Piece piece) {
		// If we don't have this piece, tell the peer we're interested in
		// getting it from him.
		if (!this.completedPieces.get(piece.getIndex()) &&
			!this.requestedPieces.get(piece.getIndex())) {
			peer.interesting();
		}

		piece.seenAt(peer);
		this.rarest.remove(piece);
		this.rarest.add(piece);

		logger.trace("Peer {} contributes {} piece(s) [{}/{}/{}].",
			new Object[] {
				peer,
				peer.getAvailablePieces().cardinality(),
				this.completedPieces.cardinality(),
				this.getAvailablePieces().cardinality(),
				this.pieces.length
			});

		if (!peer.isChoked() &&
			peer.isInteresting() &&
			!peer.isDownloading()) {
			this.handlePeerReady(peer);
		}
	}

	/** Bitfield availability handler.
	 *
	 * Handle updates in piece availability from a peer's BITFIELD message.
	 * When this happens, we need to mark in all the pieces the peer has that
	 * they can be reached through this peer, thus augmenting the global
	 * availability of pieces.
	 *
	 * @param peer The peer we got the update from.
	 * @param availablePieces The pieces availability bitfield of the peer.
	 */
	@Override
	public synchronized void handleBitfieldAvailability(SharingPeer peer,
			BitSet availablePieces) {
		// Determine if the peer is interesting for us or not, and notify it.
		BitSet interesting = (BitSet)availablePieces.clone();
		interesting.andNot(this.completedPieces);
		interesting.andNot(this.requestedPieces);

		if (interesting.cardinality() == 0) {
			peer.notInteresting();
		} else {
			peer.interesting();
		}

		// Record the peer has all the pieces it told us it had.
		for (int i = availablePieces.nextSetBit(0); i >= 0;
				i = availablePieces.nextSetBit(i+1)) {
			this.pieces[i].seenAt(peer);
			this.rarest.remove(this.pieces[i]);
			this.rarest.add(this.pieces[i]);
		}

		logger.trace("Peer {} contributes {} piece(s) [{}/{}/{}].",
			new Object[] {
				peer,
				availablePieces.cardinality(),
				this.completedPieces.cardinality(),
				this.getAvailablePieces().cardinality(),
				this.pieces.length
			});
	}

	/** Piece upload completion handler.
	 *
	 * When a piece has been sent to a peer, we just record that we sent that
	 * many bytes. If the piece is valid on the peer's side, it will send us a
	 * HAVE message and we'll record that the piece is available on the peer at
	 * that moment (see <code>handlePieceAvailability()</code>).
	 *
	 * @param peer The peer we got this piece from.
	 * @param piece The piece in question.
	 */
	@Override
	public synchronized void handlePieceSent(SharingPeer peer, Piece piece) {
		logger.trace("Completed upload of {} to {}.", piece, peer);
		this.uploaded += piece.size();
	}

	/** Piece download completion handler.
	 *
	 * If the complete piece downloaded is valid, we can record in the torrent
	 * completedPieces bitfield that we know have this piece.
	 *
	 * @param peer The peer we got this piece from.
	 * @param piece The piece in question.
	 */
	@Override
	public synchronized void handlePieceCompleted(SharingPeer peer,
		Piece piece) throws IOException {
		// Regardless of validity, record the number of bytes downloaded and
		// mark the piece as not requseted anymore
		this.downloaded += piece.size();
		this.requestedPieces.set(piece.getIndex(), false);

		if (piece.isValid()) {
			logger.trace("Validated download of {} from {}.", piece, peer);
			this.markCompleted(piece);
		} else {
			// When invalid, remark that piece as non-requested.
			logger.warn("Downloaded piece {} was nod valid ;-(", piece);
		}

		logger.trace("We now have {} piece(s) and {} outstanding request(s): {}.",
			new Object[] {
				this.completedPieces.cardinality(),
				this.requestedPieces.cardinality(),
				this.requestedPieces
			});
	}

	/** Peer disconnection handler.
	 *
	 * When a peer disconnect, we need to mark in all of the pieces it had
	 * available that they can't be reached through this peer anymore.
	 *
	 * @param peer The peer we got this piece from.
	 */
	@Override
	public synchronized void handlePeerDisconnected(SharingPeer peer) {
		BitSet availablePieces = peer.getAvailablePieces();

		for (int i = availablePieces.nextSetBit(0); i >= 0;
				i = availablePieces.nextSetBit(i+1)) {
			this.pieces[i].noLongerAt(peer);
			this.rarest.remove(this.pieces[i]);
			this.rarest.add(this.pieces[i]);
		}

		Piece requested = peer.getRequestedPiece();
		if (requested != null) {
			this.requestedPieces.set(requested.getIndex(), false);
		}

		logger.debug("Peer {} went away with {} piece(s) [{}/{}/{}].",
			new Object[] {
				peer,
				availablePieces.cardinality(),
				this.completedPieces.cardinality(),
				this.getAvailablePieces().cardinality(),
				this.pieces.length
			});
		logger.trace("We now have {} piece(s) and {} outstanding request(s): {}",
			new Object[] {
				this.completedPieces.cardinality(),
				this.requestedPieces.cardinality(),
				this.requestedPieces
			});
	}

	@Override
	public synchronized void handleIOException(SharingPeer peer,
			IOException ioe) { /* Do nothing */ }
}
