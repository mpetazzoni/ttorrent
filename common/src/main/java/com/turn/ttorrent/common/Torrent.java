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
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.NoSuchAlgorithmException;
import java.util.*;

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
public class Torrent extends Observable implements TorrentInfo, AnnounceableTorrent, TorrentMultiFileMetadata {

  /**
   * The query parameters encoding when parsing byte strings.
   */
  public static final String BYTE_ENCODING = "ISO-8859-1";
  private static final Logger logger = TorrentLoggerFactory.getLogger();

  public static final int PIECE_HASH_SIZE = 20;


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
  private final byte[] myPiecesHashes;

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
    myPiecesHashes = decoded_info.get("pieces").getBytes();
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
  public Optional<String> getComment() {
    return Optional.of(this.comment);
  }

  /**
   * Get this torrent's creator (user, software, whatever...).
   */
  public Optional<String> getCreatedBy() {
    return Optional.of(this.createdBy);
  }

  @Override
  public String getDirectoryName() {
    return this.name;
  }

  @Override
  public List<TorrentFile> getFiles() {
    return files;
  }

  @Override
  public Optional<Long> getCreationDate() {
    return Optional.of(creationDate == null ? null : creationDate.getTime()/1000);
  }

  @Override
  public int getPieceLength() {
    return (int)myPieceLength;
  }

  @Override
  public byte[] getPiecesHashes() {
    return myPiecesHashes;
  }

  @Override
  public boolean isPrivate() {
    return false;
  }

  @Override
  public int getPiecesCount() {
    return myPieceCount;
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

  @NotNull
  public String getAnnounce() {
    return trackers.get(0).get(0).toString();
  }

  /**
   * Tells whether we were an initial seeder for this torrent.
   */
  public boolean isSeeder() {
    return this.seeder;
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
}
