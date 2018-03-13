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
package com.turn.ttorrent.common;

import com.turn.ttorrent.bcodec.BDecoder;
import com.turn.ttorrent.bcodec.BEValue;
import com.turn.ttorrent.bcodec.BEncoder;
import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.PatternLayout;
import org.slf4j.Logger;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A torrent file tracked by the controller's BitTorrent tracker.
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
public class Torrent extends Observable implements TorrentInfo, AnnounceableTorrent {

  private static final Logger logger = TorrentLoggerFactory.getLogger();

  /**
   * Torrent file piece length (in bytes), we use 512 kB.
   */
  public static final int DEFAULT_PIECE_LENGTH = 512 * 1024;

  public static final int PIECE_HASH_SIZE = 20;
  private static final int HASHING_TIMEOUT_SEC = 15;

  /**
   * The query parameters encoding when parsing byte strings.
   */
  public static final String BYTE_ENCODING = "ISO-8859-1";

  public static int HASHING_THREADS_COUNT = Runtime.getRuntime().availableProcessors();


  private static final ExecutorService HASHING_EXECUTOR = Executors.newFixedThreadPool(HASHING_THREADS_COUNT, new ThreadFactory() {
    @Override
    public Thread newThread(final Runnable r) {
      final Thread thread = new Thread(r);
      thread.setDaemon(true);
      return thread;
    }
  });


  protected final byte[] encoded;
//	protected final byte[] encoded_info;
//	protected final Map<String, BEValue> decoded;
//	protected final Map<String, BEValue> decoded_info;

  private final byte[] info_hash;
  private final String hex_info_hash;

  private final List<List<URI>> trackers;
  private final Set<URI> allTrackers;
  private final Date creationDate;
  private final String comment;
  private final String createdBy;
  private final String name;
  private final long size;
  protected final List<TorrentFile> files;

  private final boolean seeder;

  private final int myPieceCount;
  private final long myPieceLength;

  /**
   * Create a new torrent from meta-info binary data.
   *
   * Parses the meta-info data (which should be B-encoded as described in the
   * BitTorrent specification) and create a Torrent object from it.
   *
   * @param torrent The meta-info byte data.
   * @param seeder  Whether we'll be seeding for this torrent or not.
   * @throws IOException              When the info dictionary can't be read or
   *                                  encoded and hashed back to create the torrent's SHA-1 hash.
   * @throws NoSuchAlgorithmException If the SHA-1 algorithm is not
   *                                  available.
   */
  public Torrent(final byte[] torrent, final boolean seeder)
          throws IOException, NoSuchAlgorithmException {
    this.seeder = seeder;
    encoded = torrent;

    final Map<String, BEValue> decoded = getDecoded();

    final Map<String, BEValue> decoded_info = decoded.get("info").getMap();
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    BEncoder.bencode(decoded_info, baos);
    byte[] encoded_info = baos.toByteArray();
    this.info_hash = TorrentUtils.calculateSha1Hash(encoded_info);
    this.hex_info_hash = TorrentUtils.byteArrayToHexString(this.info_hash);

    /**
     * Parses the announce information from the decoded meta-info
     * structure.
     *
     * <p>
     * If the torrent doesn't define an announce-list, use the mandatory
     * announce field value as the single tracker in a single announce
     * tier.  Otherwise, the announce-list must be parsed and the trackers
     * from each tier extracted.
     * </p>
     *
     * @see <a href="http://bittorrent.org/beps/bep_0012.html">BitTorrent BEP#0012 "Multitracker Metadata Extension"</a>
     */
    try {
      this.trackers = new ArrayList<List<URI>>();
      this.allTrackers = new HashSet<URI>();

      if (decoded.containsKey("announce-list")) {
        List<BEValue> tiers = decoded.get("announce-list").getList();
        for (BEValue tv : tiers) {
          List<BEValue> trackers = tv.getList();
          if (trackers.isEmpty()) {
            continue;
          }

          List<URI> tier = new ArrayList<URI>();
          for (BEValue tracker : trackers) {
            URI uri = new URI(tracker.getString());

            // Make sure we're not adding duplicate trackers.
            if (!this.allTrackers.contains(uri)) {
              tier.add(uri);
              this.allTrackers.add(uri);
            }
          }

          // Only add the tier if it's not empty.
          if (!tier.isEmpty()) {
            this.trackers.add(tier);
          }
        }
      } else if (decoded.containsKey("announce")) {
        URI tracker = new URI(decoded.get("announce").getString());
        this.allTrackers.add(tracker);

        // Build a single-tier announce list.
        List<URI> tier = new ArrayList<URI>();
        tier.add(tracker);
        this.trackers.add(tier);
      }
    } catch (URISyntaxException use) {
      throw new IOException(use);
    }

    this.creationDate = decoded.containsKey("creation date")
            ? new Date(decoded.get("creation date").getLong() * 1000)
            : null;
    this.comment = decoded.containsKey("comment")
            ? decoded.get("comment").getString()
            : null;
    this.createdBy = decoded.containsKey("created by")
            ? decoded.get("created by").getString()
            : null;
    this.name = decoded_info.get("name").getString();

    this.files = new LinkedList<TorrentFile>();

    // Parse multi-file torrent file information structure.
    if (decoded_info.containsKey("files")) {
      for (BEValue file : decoded_info.get("files").getList()) {
        Map<String, BEValue> fileInfo = file.getMap();
        List<String> path = new ArrayList<String>();
        path.add(this.name);
        for (BEValue pathElement : fileInfo.get("path").getList()) {
          path.add(pathElement.getString());
        }
        this.files.add(new TorrentFile(
                path,
                fileInfo.get("length").getLong(),
                null));
      }
    } else {
      // For single-file torrents, the name of the torrent is
      // directly the name of the file.
      this.files.add(new TorrentFile(
              Collections.singletonList(this.name),
              decoded_info.get("length").getLong(),
              null));
    }

    // Calculate the total size of this torrent from its files' sizes.
    long size = 0;
    for (TorrentFile file : this.files) {
      size += file.size;
    }
    this.size = size;

    logger.debug("{}-file torrent information:",
            this.isMultifile() ? "Multi" : "Single");
    logger.debug("  Torrent name: {}", this.name);
    logger.debug("  Torrent hash: {}", this.getHexInfoHash());
    logger.debug("  Announced at:" + (this.trackers.size() == 0 ? " Seems to be trackerless" : ""));
    for (int i = 0; i < this.trackers.size(); i++) {
      List<URI> tier = this.trackers.get(i);
      for (int j = 0; j < tier.size(); j++) {
        logger.debug("    {}{}",
                (j == 0 ? String.format("%2d. ", i + 1) : "    "),
                tier.get(j));
      }
    }

    if (this.creationDate != null) {
      logger.debug("  Created on..: {}", this.creationDate);
    }

    if (this.isMultifile()) {
      logger.debug("  Found {} file(s) in multi-file torrent structure.",
              this.files.size());
      int i = 0;
      for (TorrentFile file : this.files) {
        logger.debug("    {}. {} ({} byte(s))",
                new Object[]{
                        String.format("%2d", ++i),
                        file.getRelativePathAsString(),
                        String.format("%,d", file.size)
                });
      }
    }

    logger.debug("  Pieces......: {} piece(s) ({} byte(s)/piece)",
            (this.size / decoded_info.get("piece length").getInt()) + 1,
            decoded_info.get("piece length").getInt());
    logger.debug("  Total size..: {} byte(s)",
            String.format("%,d", this.size));

    myPieceLength = decoded_info.get("piece length").getInt();
    myPieceCount = (int) (Math.ceil(
            (double) this.getSize() / myPieceLength));
  }

  /**
   * Get this torrent's name.
   *
   * <p>
   * For a single-file torrent, this is usually the name of the file. For a
   * multi-file torrent, this is usually the name of a top-level directory
   * containing those files.
   * </p>
   */
  public String getName() {
    return this.name;
  }

  /**
   * Get this torrent's comment string.
   */
  public String getComment() {
    return this.comment;
  }

  /**
   * Get this torrent's creator (user, software, whatever...).
   */
  public String getCreatedBy() {
    return this.createdBy;
  }

  /**
   * Get the total size of this torrent.
   */
  public long getSize() {
    return this.size;
  }

  /**
   * Get the file names from this torrent.
   *
   * @return The list of relative filenames of all the files described in
   * this torrent.
   */
  public List<String> getFilenames() {
    List<String> filenames = new LinkedList<String>();
    for (TorrentFile file : this.files) {
      filenames.add(file.getRelativePathAsString());
    }
    return filenames;
  }

  /**
   * Tells whether this torrent is multi-file or not.
   */
  public boolean isMultifile() {
    return this.files.size() > 1;
  }

  /**
   * Return the hash of the B-encoded meta-info structure of this torrent.
   */
  public byte[] getInfoHash() {
    return this.info_hash;
  }

  /**
   * Get this torrent's info hash (as an hexadecimal-coded string).
   */
  public String getHexInfoHash() {
    return this.hex_info_hash;
  }

  /**
   * Get the number of bytes uploaded for this torrent.
   */
  public long getUploaded() {
    return 0;
  }

  /**
   * Get the number of bytes downloaded for this torrent.
   * <p/>
   * <p>
   * <b>Note:</b> this could be more than the torrent's length, and should
   * not be used to determine a completion percentage.
   * </p>
   */
  public long getDownloaded() {
    return 0;
  }

  /**
   * Get the number of bytes left to download for this torrent.
   */
  public long getLeft() {
    return 0;
  }

  /**
   * Get the number of pieces in this torrent.
   */
  public int getPieceCount() {

    return this.myPieceCount;
  }

  public long getPieceSize(final int pieceIdx) {
    if (pieceIdx >= 0 && pieceIdx < myPieceCount - 1) {
      return myPieceLength;
    } else if (pieceIdx == myPieceCount - 1) {
      return (int) Math.min(myPieceLength, size);
    } else {
      return 0;
    }
  }


  /**
   * Return a human-readable representation of this torrent object.
   *
   * <p>
   * The torrent's name is used.
   * </p>
   */
  public String toString() {
    return this.getName();
  }

  /**
   * Return the B-encoded meta-info of this torrent.
   */
  public byte[] getEncoded() {
    return this.encoded;
  }

  protected byte[] getEncodedInfo() throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    BEncoder.bencode(getDecodedInfo(), baos);
    return baos.toByteArray();
  }

  protected Map<String, BEValue> getDecoded() throws IOException {
    return BDecoder.bdecode(new ByteArrayInputStream(encoded)).getMap();
  }

  protected Map<String, BEValue> getDecodedInfo() throws IOException {
    return getDecoded().get("info").getMap();
  }

  /**
   * Return the trackers for this torrent.
   */
  public List<List<String>> getAnnounceList() {
    List<List<String>> result = new ArrayList<List<String>>();
    for (List<URI> uris : trackers) {
      List<String> list = new ArrayList<String>();
      for (URI uri : uris) {
        list.add(uri.toString());
      }
      result.add(list);
    }
    return result;
  }

  public String getAnnounce() {
    return trackers.get(0).get(0).toString();
  }

  /**
   * Returns the number of trackers for this torrent.
   */
  public int getTrackerCount() {
    return this.allTrackers.size();
  }

  /**
   * Tells whether we were an initial seeder for this torrent.
   */
  public boolean isSeeder() {
    return this.seeder;
  }

  /**
   * Save this torrent meta-info structure into a .torrent file.
   *
   * @param file The file to write to.
   * @throws IOException If an I/O error occurs while writing the file.
   */
  public void save(File file) throws IOException {
    FileOutputStream fOut = null;
    try {
      fOut = new FileOutputStream(file);
      fOut.write(this.getEncoded());
    } finally {
      if (fOut != null) {
        fOut.close();
      }
    }
  }

  /** Torrent loading ---------------------------------------------------- */

  /**
   * Load a torrent from the given torrent file.
   *
   * <p>
   * This method assumes we are not a seeder and that local data needs to be
   * validated.
   * </p>
   *
   * @param torrent The abstract {@link File} object representing the
   *                <tt>.torrent</tt> file to load.
   * @throws IOException              When the torrent file cannot be read.
   * @throws NoSuchAlgorithmException
   */
  public static Torrent load(File torrent)
          throws IOException, NoSuchAlgorithmException {
    return Torrent.load(torrent, false);
  }

  /**
   * Load a torrent from the given torrent file.
   *
   * @param torrent The abstract {@link File} object representing the
   *                <tt>.torrent</tt> file to load.
   * @param seeder  Whether we are a seeder for this torrent or not (disables
   *                local data validation).
   * @throws IOException              When the torrent file cannot be read.
   * @throws NoSuchAlgorithmException
   */
  public static Torrent load(File torrent, boolean seeder)
          throws IOException, NoSuchAlgorithmException {
    FileInputStream fis = null;
    try {
      fis = new FileInputStream(torrent);
      byte[] data = new byte[(int) torrent.length()];
      fis.read(data);
      return new Torrent(data, seeder);
    } finally {
      if (fis != null) {
        fis.close();
      }
    }
  }

  /** Torrent creation --------------------------------------------------- */

  /**
   * Create a {@link Torrent} object for a file.
   *
   * <p>
   * Hash the given file to create the {@link Torrent} object representing
   * the Torrent metainfo about this file, needed for announcing and/or
   * sharing said file.
   * </p>
   *
   * @param source    The file to use in the torrent.
   * @param announce  The announce URI that will be used for this torrent.
   * @param createdBy The creator's name, or any string identifying the
   *                  torrent's creator.
   */
  public static Torrent create(File source, URI announce, String createdBy)
          throws NoSuchAlgorithmException, InterruptedException, IOException {
    return Torrent.create(source, null, announce, createdBy);
  }

  /**
   * Create a {@link Torrent} object for a set of files.
   *
   * <p>
   * Hash the given files to create the multi-file {@link Torrent} object
   * representing the Torrent meta-info about them, needed for announcing
   * and/or sharing these files. Since we created the torrent, we're
   * considering we'll be a full initial seeder for it.
   * </p>
   *
   * @param parent    The parent directory or location of the torrent files,
   *                  also used as the torrent's name.
   * @param files     The files to add into this torrent.
   * @param announce  The announce URI that will be used for this torrent.
   * @param createdBy The creator's name, or any string identifying the
   *                  torrent's creator.
   */
  public static Torrent create(File parent, List<File> files, URI announce,
                               String createdBy) throws NoSuchAlgorithmException,
          InterruptedException, IOException {
    return Torrent.create(parent, files, announce, null, createdBy);
  }

  /**
   * Create a {@link Torrent} object for a file.
   *
   * <p>
   * Hash the given file to create the {@link Torrent} object representing
   * the Torrent metainfo about this file, needed for announcing and/or
   * sharing said file.
   * </p>
   *
   * @param source       The file to use in the torrent.
   * @param announceList The announce URIs organized as tiers that will
   *                     be used for this torrent
   * @param createdBy    The creator's name, or any string identifying the
   *                     torrent's creator.
   */
  public static Torrent create(File source, List<List<URI>> announceList,
                               String createdBy) throws NoSuchAlgorithmException,
          InterruptedException, IOException {
    return Torrent.create(source, null, null, announceList, createdBy);
  }

  /**
   * Create a {@link Torrent} object for a set of files.
   *
   * <p>
   * Hash the given files to create the multi-file {@link Torrent} object
   * representing the Torrent meta-info about them, needed for announcing
   * and/or sharing these files. Since we created the torrent, we're
   * considering we'll be a full initial seeder for it.
   * </p>
   *
   * @param source       The parent directory or location of the torrent files,
   *                     also used as the torrent's name.
   * @param files        The files to add into this torrent.
   * @param announceList The announce URIs organized as tiers that will
   *                     be used for this torrent
   * @param createdBy    The creator's name, or any string identifying the
   *                     torrent's creator.
   */
  public static Torrent create(File source, List<File> files,
                               List<List<URI>> announceList, String createdBy)
          throws NoSuchAlgorithmException, InterruptedException, IOException {
    return Torrent.create(source, files, null, announceList, createdBy);
  }

  /**
   * Helper method to create a {@link Torrent} object for a set of files.
   *
   * <p>
   * Hash the given files to create the multi-file {@link Torrent} object
   * representing the Torrent meta-info about them, needed for announcing
   * and/or sharing these files. Since we created the torrent, we're
   * considering we'll be a full initial seeder for it.
   * </p>
   *
   * @param parent       The parent directory or location of the torrent files,
   *                     also used as the torrent's name.
   * @param files        The files to add into this torrent.
   * @param announce     The announce URI that will be used for this torrent.
   * @param announceList The announce URIs organized as tiers that will
   *                     be used for this torrent
   * @param createdBy    The creator's name, or any string identifying the
   *                     torrent's creator.
   */
  public static Torrent create(File parent, List<File> files, URI announce, List<List<URI>> announceList, String createdBy)
          throws NoSuchAlgorithmException, InterruptedException, IOException {
    return create(parent, files, announce, announceList, createdBy, DEFAULT_PIECE_LENGTH);
  }

  public static Torrent create(File parent, List<File> files, URI announce,
                               List<List<URI>> announceList, String createdBy, final int pieceSize)
          throws NoSuchAlgorithmException, InterruptedException, IOException {
    return create(parent, files, announce, announceList, createdBy, System.currentTimeMillis() / 1000, pieceSize);
  }


  //for tests
  /*package local*/
  static Torrent create(File parent, List<File> files, URI announce,
                        List<List<URI>> announceList, String createdBy, long creationTimeSecs, final int pieceSize)
          throws NoSuchAlgorithmException, InterruptedException, IOException {
    Map<String, BEValue> torrent = new HashMap<String, BEValue>();

    if (announce != null) {
      torrent.put("announce", new BEValue(announce.toString()));
    }
    if (announceList != null) {
      List<BEValue> tiers = new LinkedList<BEValue>();
      for (List<URI> trackers : announceList) {
        List<BEValue> tierInfo = new LinkedList<BEValue>();
        for (URI trackerURI : trackers) {
          tierInfo.add(new BEValue(trackerURI.toString()));
        }
        tiers.add(new BEValue(tierInfo));
      }
      torrent.put("announce-list", new BEValue(tiers));
    }
    torrent.put("creation date", new BEValue(creationTimeSecs));
    torrent.put("created by", new BEValue(createdBy));

    Map<String, BEValue> info = new TreeMap<String, BEValue>();
    info.put("name", new BEValue(parent.getName()));
    info.put("piece length", new BEValue(pieceSize));

    if (files == null || files.isEmpty()) {
      info.put("length", new BEValue(parent.length()));
      info.put("pieces", new BEValue(Torrent.hashFile(parent, pieceSize),
              Torrent.BYTE_ENCODING));
    } else {
      List<BEValue> fileInfo = new LinkedList<BEValue>();
      for (File file : files) {
        Map<String, BEValue> fileMap = new HashMap<String, BEValue>();
        fileMap.put("length", new BEValue(file.length()));

        LinkedList<BEValue> filePath = new LinkedList<BEValue>();
        while (file != null) {
          if (file.equals(parent)) {
            break;
          }

          filePath.addFirst(new BEValue(file.getName()));
          file = file.getParentFile();
        }

        fileMap.put("path", new BEValue(filePath));
        fileInfo.add(new BEValue(fileMap));
      }
      info.put("files", new BEValue(fileInfo));
      info.put("pieces", new BEValue(Torrent.hashFiles(files, pieceSize),
              Torrent.BYTE_ENCODING));
    }
    torrent.put("info", new BEValue(info));

    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    BEncoder.bencode(new BEValue(torrent), baos);
    return new Torrent(baos.toByteArray(), true);
  }

  /**
   * A {@link Callable} to hash a data chunk.
   *
   * @author mpetazzoni
   */
  private static class CallableChunkHasher implements Callable<String> {

    private final MessageDigest md;
    private final ByteBuffer data;

    CallableChunkHasher(final ByteBuffer data)
            throws NoSuchAlgorithmException {
      this.md = MessageDigest.getInstance("SHA-1");
      this.data = data;
/*
      this.data = ByteBuffer.allocate(buffer.remaining());
			buffer.mark();
			this.data.put(buffer);
			this.data.clear();
			buffer.reset();
*/
    }

    @Override
    public String call() throws UnsupportedEncodingException {
      this.md.reset();
      this.md.update(this.data.array());
      return new String(md.digest(), Torrent.BYTE_ENCODING);
    }
  }

  /**
   * Return the concatenation of the SHA-1 hashes of a file's pieces.
   *
   * <p>
   * Hashes the given file piece by piece using the default Torrent piece
   * length (see {@link #DEFAULT_PIECE_LENGTH}) and returns the concatenation of
   * these hashes, as a string.
   * </p>
   *
   * <p>
   * This is used for creating Torrent meta-info structures from a file.
   * </p>
   *
   * @param file The file to hash.
   */
  private static String hashFile(final File file, final int pieceSize)
          throws NoSuchAlgorithmException, InterruptedException, IOException {
    return Torrent.hashFiles(Arrays.asList(new File[]{file}), pieceSize);
  }

  private static String hashFiles(final List<File> files, final int pieceSize)
          throws NoSuchAlgorithmException, InterruptedException, IOException {
    if (files.size() == 0) {
      return "";
    }
    List<Future<String>> results = new LinkedList<Future<String>>();
    long length = 0L;

    final ByteBuffer buffer = ByteBuffer.allocate(pieceSize);


    final AtomicInteger threadIdx = new AtomicInteger(0);
    final String firstFileName = files.get(0).getName();

    StringBuilder hashes = new StringBuilder();

    long start = System.nanoTime();
    for (File file : files) {
      logger.debug("Analyzing local data for {} with {} threads...",
              file.getName(), HASHING_THREADS_COUNT);

      length += file.length();

      FileInputStream fis = new FileInputStream(file);
      FileChannel channel = fis.getChannel();

      try {
        while (channel.read(buffer) > 0) {
          if (buffer.remaining() == 0) {
            buffer.clear();
            final ByteBuffer data = prepareDataFromBuffer(buffer);

            results.add(HASHING_EXECUTOR.submit(new Callable<String>() {
              @Override
              public String call() throws Exception {
                Thread.currentThread().setName(String.format("%s hasher #%d", firstFileName, threadIdx.incrementAndGet()));
                return new CallableChunkHasher(data).call();
              }
            }));
          }

          if (results.size() >= HASHING_THREADS_COUNT) {
            // process hashers, otherwise they will spend too much memory
            waitForHashesToCalculate(results, hashes);
            results.clear();
          }
        }
      } finally {
        channel.close();
        fis.close();
      }
    }

    // Hash the last bit, if any
    if (buffer.position() > 0) {
      buffer.limit(buffer.position());
      buffer.position(0);
      final ByteBuffer data = prepareDataFromBuffer(buffer);
      results.add(HASHING_EXECUTOR.submit(new CallableChunkHasher(data)));
    }
    // here we have only a few hashes to wait for calculation
    waitForHashesToCalculate(results, hashes);

    long elapsed = System.nanoTime() - start;

    int expectedPieces = (int) (Math.ceil(
            (double) length / pieceSize));
    logger.debug("Hashed {} file(s) ({} bytes) in {} pieces ({} expected) in {}ms.",
            new Object[]{
                    files.size(),
                    length,
                    results.size(),
                    expectedPieces,
                    String.format("%.1f", elapsed / 1e6),
            });

    return hashes.toString();
  }

  private static ByteBuffer prepareDataFromBuffer(ByteBuffer buffer) {
    final ByteBuffer data = ByteBuffer.allocate(buffer.remaining());
    buffer.mark();
    data.put(buffer);
    data.clear();
    buffer.reset();
    return data;
  }

  private static void waitForHashesToCalculate(List<Future<String>> results, StringBuilder hashes) throws InterruptedException, IOException {
    try {
      for (Future<String> chunk : results) {
        hashes.append(chunk.get(HASHING_TIMEOUT_SEC, TimeUnit.SECONDS));
      }
    } catch (ExecutionException ee) {
      throw new IOException("Error while hashing the torrent data!", ee);
    } catch (TimeoutException e) {
      throw new RuntimeException(String.format("very slow hashing: took more than %d seconds to calculate several pieces. Cancelling", HASHING_TIMEOUT_SEC));
    }
  }

  /**
   * Sets max number of threads to use when hash for file is calculated.
   *
   * @param hashingThreadsCount number of concurrent threads for file hash calculation
   */
  public static void setHashingThreadsCount(int hashingThreadsCount) {
    Torrent.HASHING_THREADS_COUNT = hashingThreadsCount;
  }

  static {
    String threads = System.getenv("TTORRENT_HASHING_THREADS");

    if (threads != null) {
      try {
        int count = Integer.parseInt(threads);
        if (count > 0) {
          HASHING_THREADS_COUNT = count;
        }
      } catch (NumberFormatException nfe) {
        // Pass
      }
    }
  }

  /**
   * Torrent reader and creator.
   *
   * <p>
   * You can use the {@code main()} function of this {@link Torrent} class to
   * read or create torrent files. See usage for details.
   * </p>
   *
   * TODO: support multiple announce URLs.
   */
  public static void main(String[] args) {
    BasicConfigurator.configure(new ConsoleAppender(
            new PatternLayout("%d [%-25t] %-5p: %m%n")));

    if (args.length != 1 && args.length != 3) {
      System.err.println("usage: Torrent <torrent> [announce url] " +
              "[file|directory]");
      System.exit(1);
    }

    try {
      File outfile = new File(args[0]);

			/*
             * If only one argument is provided, we just want to get
			 * information about the torrent. Load the torrent to trigger the
			 * information dump and exit.
			 */
      if (args.length == 1) {
        logger.info("Dumping information on torrent {}...",
                outfile.getAbsolutePath());
        Torrent.load(outfile);
        System.exit(0);
      }

      URI announce = new URI(args[1]);
      File source = new File(args[2]);
      if (!source.exists() || !source.canRead()) {
        throw new IllegalArgumentException(
                "Cannot access source file or directory " +
                        source.getName());
      }

      String creator = String.format("%s (ttorrent)",
              System.getProperty("user.name"));

      Torrent torrent = null;
      if (source.isDirectory()) {
        File[] files = source.listFiles();
        assert files != null;
        Arrays.sort(files);
        torrent = Torrent.create(source, Arrays.asList(files),
                announce, creator);
      } else {
        torrent = Torrent.create(source, announce, creator);
      }

      torrent.save(outfile);
    } catch (Exception e) {
      logger.error("{}", e.getMessage(), e);
      System.exit(2);
    }
  }
}
