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

import java.io.FileOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.BufferedInputStream;
import java.io.*;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.List;
import java.util.LinkedList;
import java.util.Arrays;

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

	private static final Logger logger = LoggerFactory.getLogger(Torrent.class);

	/** Torrent file piece length (in bytes), we use 512 kB. */
	private static final int PIECE_LENGTH = 512 * 1024;

	public static final int PIECE_HASH_SIZE = 20;

	/** The query parameters encoding when parsing byte strings. */
	public static final String BYTE_ENCODING = "ISO-8859-1";

	protected byte[] encoded;
	protected Map<String, BEValue> decoded;

	protected byte[] encoded_info;
	protected Map<String, BEValue> decoded_info;

	private String announceUrl;
	private String name;
	private String createdBy;
	private List<TorrentFile> files;
	private byte[] info_hash;
	private String hex_info_hash;
	private long size = 0L;

	// are we going to be the seeder for this torrent?
	// if true, elimiate hashing all of the files twice
	private boolean seeder = false;

	public static class TorrentFile {
		public Long length;
		public List<String> path;

		public TorrentFile() {
			this.length = 0L;
			this.path = new LinkedList<String>();
		}

		public String getPath() {
			String parent = "";
			for (String part : this.path) {
				parent = (new File(parent, part)).getPath();
			}
			return parent;
		}
	}

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
		this(torrent, false);
	}

	/** Create a new torrent from metainfo binary data.
	 *
	 * Parses the metainfo data (which should be B-encoded as described in the
	 * BitTorrent specification) and create a Torrent object from it.
	 *
	 * @param torrent The metainfo byte data.
	 * @param seeder Are we the seeder for this torrent?
	 * @throws IllegalArgumentException When the info dictionnary can't be
	 * encoded and hashed back to create the torrent's SHA-1 hash.
	 */
	public Torrent(byte[] torrent, boolean seeder) throws IllegalArgumentException {
		this.seeder = seeder;
		this.encoded = torrent;

		try {
			this.decoded = BDecoder.bdecode(
					new ByteArrayInputStream(this.encoded)).getMap();

			this.announceUrl = this.decoded.get("announce").getString();

			this.decoded_info = this.decoded.get("info").getMap();
			this.name = this.decoded_info.get("name").getString();
			this.createdBy = this.decoded.get("created by").getString();

			if (this.decoded_info.containsKey("files")) {
				this.files = new LinkedList<TorrentFile>();

				List<BEValue> fileBEVals = this.decoded_info.get("files").getList();
				for (BEValue fileBEVal : fileBEVals) {
					Map<String, BEValue> fileMap = fileBEVal.getMap();

					TorrentFile torrentFile = new TorrentFile();
					this.size += torrentFile.length = fileMap.get("length").getLong();

					List<BEValue> pathParts = fileMap.get("path").getList();
					for (BEValue path : pathParts) {
						torrentFile.path.add(path.getString());
					}
					this.files.add(torrentFile);
				}
			} else {
				this.size = this.decoded_info.get("length").getLong();
			}

			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			BEncoder.bencode(this.decoded_info, baos);
			this.encoded_info = baos.toByteArray();
			this.info_hash = Torrent.hash(this.encoded_info);
			this.hex_info_hash = Torrent.byteArrayToHexString(this.info_hash);
		} catch (Exception e) {
			throw new IllegalArgumentException("Can't parse torrent information!", e);
		}
	}

	public boolean isMultiFile() {
		return this.decoded_info.containsKey("files");
	}

	/**
	 * Get the total size of all files or the single file.
	 */
	public long getSize() {
		return this.size;
	}

	/** Get the list of files.
	 *
	 * For a multi-file torrent, path and length of each file. 
	 */
	public List<TorrentFile> getFiles() {
		return this.files;
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

	/** Get this torrent's created by.
	 *
	 */
	public String getCreatedBy() {
		return this.createdBy;
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

	/** Return the seeder flag of this torrent.
	 */
	public boolean isSeeder() {
		return this.seeder;
	}

	public void save(File output) throws IOException {
		FileOutputStream fos = new FileOutputStream(output);
		fos.write(getEncoded());
		fos.flush();
		fos.close();
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
		logger.info("Creating torrent for " + source.getName() + "...");

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
		return new Torrent(baos.toByteArray(), true);
	}

	/** Create a {@link Torrent} object for a file.
	 *
	 * <p>
	 * Hash the given file (by filename) to create the {@link Torrent} object
	 * representing the Torrent metainfo about this file, needed for announcing
	 * and/or sharing said file.
	 * </p>
	 *
	 * @param source The suggested destination.
	 * @param A list of files to add to this torrent
	 * @param announce The announce URL that will be used for this torrent.
	 * @param createdBy The creator's name, or any string identifying the
	 * torrent's creator.
	 */
	public static Torrent create(File source, List<File> files, String announce, String createdBy)
		throws NoSuchAlgorithmException, IOException {
		logger.info("Creating torrent for " + source.getName() + "...");

		Map<String, BEValue> torrent = new HashMap<String, BEValue>();
		torrent.put("announce", new BEValue(announce));
		torrent.put("creation date", new BEValue(new Date().getTime()));
		torrent.put("created by", new BEValue(createdBy));

		Map<String, BEValue> info = new TreeMap<String, BEValue>();
		info.put("name", new BEValue(source.getName()));

		long totalLength = 0;
		List<BEValue> fileDicts = new LinkedList<BEValue>();
		for (File file : files) {
			Map<String, BEValue> fileInfo = new TreeMap<String, BEValue>();
			long fileLength = file.length();
			totalLength += fileLength;

			fileInfo.put("length", new BEValue(fileLength));
			fileInfo.put("path", new BEValue(getFilePath(source, file)));
			fileDicts.add(new BEValue(fileInfo));
		}
		info.put("files", new BEValue(fileDicts));
		info.put("piece length", new BEValue(Torrent.PIECE_LENGTH));
		info.put("pieces", new BEValue(Torrent.hashPieces(files),
					Torrent.BYTE_ENCODING));
		torrent.put("info", new BEValue(info));

		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		BEncoder.bencode(new BEValue(torrent), baos);
		return new Torrent(baos.toByteArray(), true);
	}

	private static List<BEValue> getFilePath(File source, File file) throws UnsupportedEncodingException {
		LinkedList<BEValue> path = new LinkedList<BEValue>();
		File parent = file;
		while (parent != null) {
			if (parent.equals(source)) break;
			path.addFirst(new BEValue(parent.getName()));
			parent = parent.getParentFile();
		}
		return path;
	}

	/** Open a torrent from the given torrent file.
	 *
	 * @param source The <code>.torrent</code> file to read the torrent
	 * meta-info from.
	 * @throws IOException When the torrent file cannot be read.
	 */
	public static Torrent fromFile(File source)
		throws IOException {
		FileInputStream fis = new FileInputStream(source);
		byte[] data = new byte[(int)source.length()];
		fis.read(data);
		fis.close();
		return new Torrent(data);
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
	 * pieces maps to a string whose length is a multiple of 20.
	 * It is to be subdivided into strings of length 20, each of
	 * which is the SHA1 hash of the piece at the corresponding index.
	 * </p>
	 *
	 * <p>
	 * This is used for creating Torrent meta-info structures from a file.
	 * </p>
	 *
	 * @param source The list of files to hash.
	 */
	private static String hashPieces(List<File> files)
		throws NoSuchAlgorithmException, IOException {
		long start = System.currentTimeMillis();
		long hashTime = 0L;

		StringBuffer pieces = new StringBuffer();
		StringBuffer fileStr = new StringBuffer();
		byte[] data = new byte[Torrent.PIECE_LENGTH];
		ByteBuffer buffer = ByteBuffer.allocate(Torrent.PIECE_LENGTH);

		// get total length
		int counter = 0;
		long total = 0L;
		int read;
		for (File file : files) total += file.length();

		// hash each file
		for (File file : files) {
			InputStream is = new BufferedInputStream(new FileInputStream(file));
			while ((read = is.read(data, 0, buffer.remaining())) > 0) {
				buffer.put(data, 0, read);
				if (buffer.position() == PIECE_LENGTH) {
					counter += 1;
					long hashStart = System.currentTimeMillis();
					pieces.append(hashPiece(buffer));
					hashTime += (System.currentTimeMillis() - hashStart);
					buffer.clear();
				}
			}
			fileStr.append(" " + file.getName());
			is.close();
		}
		// handle left over
		if (buffer.position() > 0) {
			pieces.append(hashPiece(buffer));
			counter += 1;
		}

		int n_pieces = new Double(Math.ceil((double)total /
					Torrent.PIECE_LENGTH)).intValue();
		logger.info("Hashed {} ({} bytes) in {} pieces, actual pieces {} in {} seconds {} hashing.", new Object[] {
					fileStr, total, n_pieces, counter,
					(System.currentTimeMillis() - start) / 1000L,
					hashTime / 1000L
					});

		return pieces.toString();
	}

	/** Return the concatenation of the SHA-1 hashes of a file's pieces.
	 *
	 * @param source The file to hash.
	 */
	private static String hashPieces(File source)
		throws NoSuchAlgorithmException, IOException {
		return hashPieces(Arrays.<File>asList(new File[] {source}));
	}

	private static String hashPiece(ByteBuffer buffer)
		throws UnsupportedEncodingException, NoSuchAlgorithmException {
		MessageDigest md = MessageDigest.getInstance("SHA-1");
		md.reset();
		md.update(buffer.array(), 0, buffer.position());
		return new String(md.digest(), Torrent.BYTE_ENCODING);
	}


	/** Main entry point creating a torrent
	 */
	public static void main(String[] args) {

		if (args.length != 3) {
			System.err.println("usage: Torrent <torrent> <announce url> <directory|file>");
			System.exit(1);
		}

		try {
			String announce = args[1];
			File outfile = new File(args[0]);
			File source = new File(args[2]);
			if (!source.exists()) {
				System.err.println("<directory|file> must exist!");
				System.exit(1);
			}
			if (source.isDirectory()) {
				// multi file torrent
				List<File> files = Arrays.asList(source.listFiles());
				Torrent t = Torrent.create(source, files, announce, "ttorrent, Bit Torrent");
				t.save(outfile);
			} else {
				// single file torrent
				Torrent t = Torrent.create(source, announce, "ttorrent, Bit Torrent");
				t.save(outfile);
			}
			
		} catch (Exception e) {
			logger.error("{}", e);
			e.printStackTrace();
			System.exit(2);
		}
	}
}
