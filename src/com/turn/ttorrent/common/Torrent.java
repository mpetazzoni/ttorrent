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
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.PatternLayout;

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

	protected final byte[] encoded;
	private final boolean seeder;

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
	 * @param seeder Whether we'll be seeding for this torrent or not.
	 * @throws IllegalArgumentException When the info dictionnary can't be
	 * encoded and hashed back to create the torrent's SHA-1 hash.
	 */
	public Torrent(byte[] torrent, boolean seeder)
		throws IllegalArgumentException {
		this.encoded = torrent;
		this.seeder = seeder;

		try {
			this.decoded = BDecoder.bdecode(
					new ByteArrayInputStream(this.encoded)).getMap();

			this.announceUrl = this.decoded.get("announce").getString();
			this.createdBy = this.decoded.containsKey("created by")
				? this.decoded.get("created by").getString()
				: null;

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

	/** Tells whether we were an initial seeder for this torrent.
	 */
	public boolean isSeeder() {
		return this.seeder;
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

	/** Determine how many threads to use for the piece hashing.
	 *
	 * <p>
	 * If the environment variable TTORRENT_HASHING_THREADS is set to an
	 * integer value greater than 0, its value will be used. Otherwise, it
	 * defaults to the number of processors detected by the Java Runtime.
	 * </p>
	 *
	 * @return How many threads to use for concurrent piece hashing.
	 */
	protected static int getHashingThreadsCount() {
		String threads = System.getenv("TTORRENT_HASHING_THREADS");

		if (threads != null) {
			try {
				int count = Integer.parseInt(threads);
				if (count > 0) {
					return count;
				}
			} catch (NumberFormatException nfe) {
				// Pass
			}
		}

		return Runtime.getRuntime().availableProcessors();
	}

	/** Create a {@link Torrent} object for a file.
	 *
	 * <p>
	 * Hash the given file (by filename) to create the {@link Torrent} object
	 * representing the Torrent metainfo about this file, needed for announcing
	 * and/or sharing said file. Since we created the torrent from a file,
	 * we're considering we'll be a full initial seeder for this torrent.
	 * </p>
	 *
	 * @param source The file name.
	 * @param announce The announce URL that will be used for this torrent.
	 * @param createdBy The creator's name, or any string identifying the
	 * torrent's creator.
	 */
	public static Torrent create(File source, URL announce,
		String createdBy)
		throws NoSuchAlgorithmException, InterruptedException, IOException {
		logger.info("Creating torrent for {}...", source.getName());

		Map<String, BEValue> torrent = new HashMap<String, BEValue>();
		torrent.put("announce", new BEValue(announce.toString()));
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
		return new Torrent(baos.toByteArray(), true);
	}

	/** A {@link Callable} to hash a data chunk.
	 *
	 * @author mpetazzoni
	 */
	private static class CallableChunkHasher implements Callable<String> {

		private final MessageDigest md;
		private final byte[] data;
		private final int length;

		CallableChunkHasher(byte[] data, int length)
			throws NoSuchAlgorithmException {
			this.md = MessageDigest.getInstance("SHA-1");
			this.data = data;
			this.length = length;
		}

		@Override
		public String call() throws UnsupportedEncodingException {
			this.md.reset();
			this.md.update(this.data, 0, this.length);
			return new String(md.digest(), Torrent.BYTE_ENCODING);
		}
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
		throws NoSuchAlgorithmException, InterruptedException, IOException {

		ExecutorService executor = Executors.newFixedThreadPool(
			getHashingThreadsCount());
		List<Future<String>> results = new LinkedList<Future<String>>();
		byte[] data = new byte[Torrent.PIECE_LENGTH];
		int pieces = 0;
		int read;

		logger.info("Analyzing local data for {} with {} threads...",
			source.getName(), getHashingThreadsCount());
		long start = System.nanoTime();
		InputStream is = new BufferedInputStream(new FileInputStream(source));
		while ((read = is.read(data)) > 0) {
			results.add(executor.submit(new CallableChunkHasher(data, read)));
			pieces++;
		}
		is.close();

		// Request orderly executor shutdown and wait for hashing tasks to
		// complete.
		executor.shutdown();
		while (!executor.isTerminated()) {
			Thread.sleep(10);
		}
		long elapsed = System.nanoTime() - start;

		StringBuffer hashes = new StringBuffer();
		try {
			for (Future<String> chunk : results) {
				hashes.append(chunk.get());
			}
		} catch (ExecutionException ee) {
			throw new IOException("Error while hashing the torrent data!", ee);
		}

		int expectedPieces = new Double(Math.ceil((double)source.length() /
			Torrent.PIECE_LENGTH)).intValue();
		logger.info("Hashed {} ({} bytes) in {} pieces ({} expected) in {}ms.",
			new Object[] {
				source.getName(),
				source.length(),
				pieces,
				expectedPieces,
				String.format("%.1f", elapsed/1024.0/1024.0),
			});

		return hashes.toString();
	}

	/** Load a torrent from the given torrent file.
	 *
	 * <p>
	 * This method assumes we are not a seeder and that local data needs to be
	 * validated.
	 * </p>
	 *
	 * @param torrent The abstract {@link File} object representing the
	 * <tt>.torrent</tt> file to load.
	 * @throws IOException When the torrent file cannot be read.
	 */
	public static Torrent load(File torrent) throws IOException {
		return Torrent.load(torrent, false);
	}

	/** Load a torrent from the given torrent file.
	 *
	 * @param torrent The abstract {@link File} object representing the
	 * <tt>.torrent</tt> file to load.
	 * @param seeder Whether we are a seeder for this torrent or not (disables
	 * local data validation).
	 * @throws IOException When the torrent file cannot be read.
	 */
	public static Torrent load(File torrent, boolean seeder)
		throws IOException {
		FileInputStream fis = new FileInputStream(torrent);
		byte[] data = new byte[(int)torrent.length()];
		fis.read(data);
		fis.close();
		return new Torrent(data, false);
	}

	/** Save this torrent meta-info structure into a .torrent file.
	 *
	 * @param output The file to write to.
	 * @throws IOException If an I/O error occurs while writing the file.
	 */
	public void save(File output) throws IOException {
		FileOutputStream fos = new FileOutputStream(output);
		fos.write(this.getEncoded());
		fos.close();
		logger.info("Wrote torrent file {}.", output.getAbsolutePath());
	}

	/** Torrent creator.
	 *
	 * <p>
	 * You can use the {@code main()} function of this {@link Torrent} class to
	 * create torrent files. See usage for details.
	 * </p>
	 */
	public static void main(String[] args) {
		BasicConfigurator.configure(new ConsoleAppender(
			new PatternLayout("%d [%-25t] %-5p: %m%n")));

		if (args.length < 3) {
			System.err.println("usage: Torrent <torrent> <announce url> <file>");
			System.exit(1);
		}

		try {
			File outfile = new File(args[0]);
			if (!outfile.canWrite()) {
				throw new IllegalArgumentException(
					"Cannot write to destination file " +
					outfile.getName() + " !");
			}

			URL announce = new URL(args[1]);
			File source = new File(args[2]);
			if (!source.exists() || !source.canRead()) {
				throw new IllegalArgumentException(
					"Cannot access source file or directory " +
					source.getName());
			}

			Torrent torrent = Torrent.create(source, announce,
				String.format("%s (ttorrent)", System.getProperty("user.name")));
			torrent.save(outfile);

			if (torrent.getHexInfoHash().equals(
				Torrent.load(outfile).getHexInfoHash())) {
				logger.info("Validated created torrent file (hash: {}).",
					torrent.getHexInfoHash());
			}
		} catch (Exception e) {
			logger.error("{}", e.getMessage(), e);
			System.exit(2);
		}
	}
}
