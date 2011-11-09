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

package com.turn.ttorrent.common;

import com.turn.ttorrent.bcodec.BDecoder;
import com.turn.ttorrent.bcodec.BEValue;
import com.turn.ttorrent.bcodec.BEncoder;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A torrent file tracked by the controller's BitTorrent tracker.
 *
 * <p>
 * This class represents an active torrent on the tracker. The torrent
 * information is kept in-memory, and is created from the byte blob one would
 * usually find in a <tt>.torrent</tt> file.
 * </p>
 *
 * <p>
 * Each torrent also keeps a repository of the peers seeding and leeching this
 * torrent from the tracker.
 * </p>
 *
 * @author mpetazzoni
 * @see <a href="http://wiki.theory.org/BitTorrentSpecification#Metainfo_File_Structure">Torrent meta-info file structure specification</a>
 */
public class Torrent {

	private static final Logger logger =
		LoggerFactory.getLogger(Torrent.class);

	/** Torrent file piece length (in bytes), we use 512 kB. */
	private static final int PIECE_LENGTH = 512 * 1024;

	public static final int PIECE_HASH_SIZE = 20;

	/** The query parameters encoding when parsing byte strings. */
	public static final String BYTE_ENCODING = "ISO-8859-1";

	protected byte[] encoded;
	protected Map<String, BEValue> decoded;

	protected byte[] encoded_info;
	protected Map<String, BEValue> decoded_info;

	private final String announceUrl;
	private final String createdBy;
	private final String name;
	private final long size;

	private byte[] info_hash;
	private String hex_info_hash;

	/** Create a new torrent from metainfo binary data.
	 *
	 * Parses the metainfo data (which should be B-encoded as described in the
	 * BitTorrent specification) and create a Torrent object from it.
	 *
	 * @param torrent The metainfo byte data.
	 * @throws IllegalArgumentException When the info dictionnary can't be
	 * encoded and hashed back to create the torrent's SHA-1 hash.
	 */
	public Torrent(byte[] torrent) throws IllegalArgumentException {
		this.encoded = torrent;

		try {
			this.decoded = BDecoder.bdecode(
					new ByteArrayInputStream(this.encoded)).getMap();

			this.announceUrl = this.decoded.get("announce").getString();
			this.createdBy = this.decoded.get("created by").getString();

			this.decoded_info = this.decoded.get("info").getMap();
			this.name = this.decoded_info.get("name").getString();
			this.size = this.decoded_info.get("length").getLong();

			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			BEncoder.bencode(this.decoded_info, baos);
			this.encoded_info = baos.toByteArray();
			this.info_hash = Torrent.hash(this.encoded_info);
			this.hex_info_hash = Torrent.byteArrayToHexString(this.info_hash);
		} catch (Exception e) {
			throw new IllegalArgumentException("Can't parse torrent information!", e);
		}
	}

	/** Get this torrent's name.
	 *
	 * For a single-file torrent, this is usually the name of the file. For a
	 * multi-file torrent, this is usually the name of a top-level directory
	 * containing those files.
	 */
	public String getName() {
		return this.name;
	}

	/** Get this torrent's creator (user, software, whatever...).
	 */
	public String getCreatedBy() {
		return this.createdBy;
	}

	/** Get the total size of this torrent.
	 */
	public long getSize() {
		return this.size;
	}

	/** Return the hash of the B-encoded meta-info structure of this torrent.
	 */
	public byte[] getInfoHash() {
		return this.info_hash;
	}

	/** Get this torrent's info hash (as an hexadecimal-coded string).
	 */
	public String getHexInfoHash() {
		return this.hex_info_hash;
	}

	/** Return a human-readable representation of this torrent object.
	 *
	 * The torrent's name is used.
	 */
	public String toString() {
		return this.getName();
	}

	/** Return the B-encoded meta-info of this torrent.
	 */
	public byte[] getEncoded() {
		return this.encoded;
	}

	/** Return the announce URL used by this torrent.
	 */
	public String getAnnounceUrl() {
		return this.announceUrl;
	}

	public static byte[] hash(byte[] data) throws NoSuchAlgorithmException {
		MessageDigest md = MessageDigest.getInstance("SHA-1");
		md.update(data);
		return md.digest();
	}

	/** Convert a byte string to a string containing an hexadecimal
	 * representation of the original data.
	 *
	 * @param bytes The byte array to convert.
	 */
	public static String byteArrayToHexString(byte[] bytes) {
		BigInteger bi = new BigInteger(1, bytes);
		return String.format("%0" + (bytes.length << 1) + "X", bi);
	}

	/** Return an hexadecimal representation of the bytes contained in the
	 * given string, following the default, expected byte encoding.
	 *
	 * @param input The input string.
	 */
	public static String toHexString(String input) {
		try {
			byte[] bytes = input.getBytes(Torrent.BYTE_ENCODING);
			return Torrent.byteArrayToHexString(bytes);
		} catch (UnsupportedEncodingException uee) {
			return null;
		}
	}

	/** Create a {@link Torrent} object for a file.
	 *
	 * <p>
	 * Hash the given file (by filename) to create the {@link Torrent} object
	 * representing the Torrent metainfo about this file, needed for announcing
	 * and/or sharing said file.
	 * </p>
	 *
	 * @param source The file name.
	 * @param announce The announce URL that will be used for this torrent.
	 * @param createdBy The creator's name, or any string identifying the
	 * torrent's creator.
	 */
	public static Torrent create(File source, String announce, String createdBy)
		throws NoSuchAlgorithmException, IOException {
		logger.info("Creating torrent for {}...", source.getName());

		Map<String, BEValue> torrent = new HashMap<String, BEValue>();
		torrent.put("announce", new BEValue(announce));
		torrent.put("creation date", new BEValue(new Date().getTime()));
		torrent.put("created by", new BEValue(createdBy));

		Map<String, BEValue> info = new TreeMap<String, BEValue>();
		info.put("length", new BEValue(source.length()));
		info.put("name", new BEValue(source.getName()));
		info.put("piece length", new BEValue(Torrent.PIECE_LENGTH));
		info.put("pieces", new BEValue(Torrent.hashPieces(source),
					Torrent.BYTE_ENCODING));
		torrent.put("info", new BEValue(info));

		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		BEncoder.bencode(new BEValue(torrent), baos);
		return new Torrent(baos.toByteArray());
	}

	/** Return the concatenation of the SHA-1 hashes of a file's pieces.
	 *
	 * <p>
	 * Hashes the given file piece by piece using the default Torrent piece
	 * length (see {@link #PIECE_LENGTH}) and returns the concatenation of
	 * these hashes, as a string.
	 * </p>
	 *
	 * <p>
	 * This is used for creating Torrent meta-info structures from a file.
	 * </p>
	 *
	 * @param source The file to hash.
	 */
	private static String hashPieces(File source)
		throws NoSuchAlgorithmException, IOException {
		long start = System.nanoTime();
		long hashTime = 0L;

		MessageDigest md = MessageDigest.getInstance("SHA-1");
		InputStream is = new BufferedInputStream(new FileInputStream(source));
		StringBuffer pieces = new StringBuffer();
		byte[] data = new byte[Torrent.PIECE_LENGTH];
		int read;

		while ((read = is.read(data)) > 0) {
			md.reset();
			md.update(data, 0, read);

			long hashStart = System.nanoTime();
			pieces.append(new String(md.digest(), Torrent.BYTE_ENCODING));
			hashTime += (System.nanoTime() - hashStart);
		}
		is.close();

		int n_pieces = new Double(Math.ceil((double)source.length() /
			Torrent.PIECE_LENGTH)).intValue();
		logger.info("Hashed {} ({} bytes) in {} pieces (total: {}ms, " +
			"{}ms hashing).",
			new Object[] {
				source.getName(),
				source.length(),
				n_pieces,
				String.format("%.1f", (System.nanoTime() - start) / 1024),
				String.format("%.1f", hashTime / 1024),
			});

		return pieces.toString();
	}
}
