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

import com.turn.ttorrent.bcodec.BEValue;

import com.turn.ttorrent.bcodec.BytesBDecoder;
import com.turn.ttorrent.bcodec.BytesBEncoder;
import com.turn.ttorrent.bcodec.StreamBDecoder;
import com.turn.ttorrent.bcodec.StreamBEncoder;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;
import javax.annotation.WillNotClose;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.Charsets;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
public class Torrent {

    private static final Logger logger =
            LoggerFactory.getLogger(Torrent.class);
    /** Torrent file piece length (in bytes), we use 512 kB. */
    public static final int PIECE_HASH_SIZE = 20;
    /** The query parameters encoding when parsing byte strings. */
    public static final Charset BYTE_ENCODING = Charsets.ISO_8859_1;
    public static final String BYTE_ENCODING_NAME = BYTE_ENCODING.name();

    /**
     *
     * @author dgiffin
     * @author mpetazzoni
     */
    public static class TorrentFile {

        public final String path;
        public final long size;

        public TorrentFile(@Nonnull String path, @Nonnegative long size) {
            this.path = path;
            this.size = size;
        }
    }
    private final Map<String, BEValue> decoded;
    private final Map<String, BEValue> decoded_info;
    private final byte[] info_hash;
    private final List<List<URI>> trackers = new ArrayList<List<URI>>();
    private final long creationTime;
    private final String comment;
    private final String createdBy;
    private final String name;
    private final long size;
    private final List<TorrentFile> files = new ArrayList<TorrentFile>();
    private final int pieceLength;
    private final byte[] piecesHashes;

    @Nonnull
    private static Map<String, BEValue> load(@Nonnull File file) throws IOException {
        InputStream in = FileUtils.openInputStream(file);
        try {
            return new StreamBDecoder(in).bdecodeMap().getMap();
        } finally {
            IOUtils.closeQuietly(in);
        }
    }

    /**
     * Load a torrent from the given torrent file.
     *
     * @param torrent The abstract {@link File} object representing the
     * <tt>.torrent</tt> file to load.
     * @throws IOException When the torrent file cannot be read.
     */
    public Torrent(@Nonnull File torrent) throws IOException, URISyntaxException {
        this(load(torrent));
    }

    /**
     * Create a new torrent from meta-info binary data.
     *
     * Parses the meta-info data (which should be B-encoded as described in the
     * BitTorrent specification) and create a Torrent object from it.
     *
     * @param torrent The meta-info byte data.
     * @throws IOException When the info dictionary can't be read or
     * encoded and hashed back to create the torrent's SHA-1 hash.
     */
    public Torrent(@Nonnull byte[] torrent) throws IOException, URISyntaxException {
        this(new BytesBDecoder(torrent).bdecodeMap().getMap());
    }

    public Torrent(@Nonnull Map<String, BEValue> torrent) throws IOException, URISyntaxException {
        this.decoded = torrent;
        this.decoded_info = this.decoded.get("info").getMap();

        BytesBEncoder encoder = new BytesBEncoder();
        encoder.bencode(decoded_info);
        this.info_hash = Torrent.hash(encoder.toByteArray());

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
        Set<URI> allTrackers = new HashSet<URI>();

        if (this.decoded.containsKey("announce-list")) {
            List<BEValue> in_tiers = this.decoded.get("announce-list").getList();
            for (BEValue in_tier_bvalue : in_tiers) {
                List<BEValue> in_tier = in_tier_bvalue.getList();
                if (in_tier.isEmpty())
                    continue;

                List<URI> out_tier = new ArrayList<URI>();
                for (BEValue in_tracker : in_tier) {
                    URI uri = new URI(in_tracker.getString());

                    // Make sure we're not adding duplicate trackers.
                    if (!allTrackers.contains(uri)) {
                        out_tier.add(uri);
                        allTrackers.add(uri);
                    }
                }

                // Only add the tier if it's not empty.
                if (!out_tier.isEmpty())
                    this.trackers.add(out_tier);
            }
        } else if (this.decoded.containsKey("announce")) {
            URI tracker = new URI(this.decoded.get("announce").getString());
            // Build a single-tier announce list.
            this.trackers.add(Arrays.asList(tracker));
        }

        this.creationTime = this.decoded.containsKey("creation date")
                ? this.decoded.get("creation date").getLong() * 1000
                : -1L;
        this.comment = this.decoded.containsKey("comment")
                ? this.decoded.get("comment").getString()
                : null;
        this.createdBy = this.decoded.containsKey("created by")
                ? this.decoded.get("created by").getString()
                : null;
        this.name = this.decoded_info.get("name").getString();

        // Parse multi-file torrent file information structure.
        if (this.decoded_info.containsKey("files")) {
            for (BEValue file : this.decoded_info.get("files").getList()) {
                Map<String, BEValue> fileInfo = file.getMap();
                StringBuilder path = new StringBuilder(this.name);
                for (BEValue pathElement : fileInfo.get("path").getList()) {
                    path.append(File.separator).append(pathElement.getString());
                }
                this.files.add(new TorrentFile(
                        path.toString(),
                        fileInfo.get("length").getLong()));
            }
        } else {
            // For single-file torrents, the name of the torrent is
            // directly the name of the file.
            this.files.add(new TorrentFile(
                    this.name,
                    this.decoded_info.get("length").getLong()));
        }

        // Calculate the total size of this torrent from its files' sizes.
        long size = 0;
        for (TorrentFile file : this.files)
            size += file.size;
        this.size = size;

        this.pieceLength = this.decoded_info.get("piece length").getInt();
        this.piecesHashes = this.decoded_info.get("pieces").getBytes();

        if (this.piecesHashes.length / Torrent.PIECE_HASH_SIZE
                * (long) this.pieceLength < this.size) {
            throw new IllegalArgumentException("Torrent size does not "
                    + "match the number of pieces and the piece size!");
        }


        logger.info("{}-file torrent information:",
                this.isMultifile() ? "Multi" : "Single");
        logger.info("  Torrent name: {}", this.name);
        logger.info("  Announced at:" + (trackers.isEmpty() ? " Seems to be trackerless" : ""));
        for (int i = 0; i < this.trackers.size(); i++) {
            List<URI> tier = this.trackers.get(i);
            for (int j = 0; j < tier.size(); j++) {
                logger.info("    {}{}",
                        (j == 0 ? String.format("%2d. ", i + 1) : "    "),
                        tier.get(j));
            }
        }

        if (this.creationTime > 0)
            logger.info("  Created on..: {}", new Date(this.creationTime));
        if (this.comment != null)
            logger.info("  Comment.....: {}", this.comment);
        if (this.createdBy != null)
            logger.info("  Created by..: {}", this.createdBy);

        if (this.isMultifile()) {
            logger.info("  Found {} file(s) in multi-file torrent structure.",
                    this.files.size());
            int i = 0;
            for (TorrentFile file : this.files) {
                logger.debug("    {}. {} ({} byte(s))",
                        new Object[]{
                    String.format("%2d", ++i),
                    file.path,
                    String.format("%,d", file.size)
                });
            }
        }

        logger.info("  Pieces......: {} piece(s) ({} byte(s)/piece)",
                (this.size / this.decoded_info.get("piece length").getInt()) + 1,
                this.decoded_info.get("piece length").getInt());
        logger.info("  Total size..: {} byte(s)",
                String.format("%,d", this.size));
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
    @Nonnegative
    public long getSize() {
        return this.size;
    }

    @Nonnull
    public List<TorrentFile> getFiles() {
        return files;
    }

    /**
     * Get the file names from this torrent.
     *
     * @return The list of relative filenames of all the files described in
     * this torrent.
     */
    public List<String> getFilenames() {
        List<String> filenames = new ArrayList<String>(files.size());
        for (TorrentFile file : this.files)
            filenames.add(file.path);
        return filenames;
    }

    @Nonnegative
    public int getPieceCount() {
        return (int) (Math.ceil((double) getSize() / getPieceLength()));
    }

    @Nonnegative
    public long getPieceOffset(@Nonnegative int index) {
        return (long) getPieceLength() * (long) index;
    }

    @Nonnegative
    public int getPieceLength() {
        return pieceLength;
    }

    /**
     * Returns the size, in bytes, of the given piece.
     *
     * <p>
     * All pieces, except the last one, are expected to have the same size.
     * </p>
     */
    @Nonnegative
    public int getPieceLength(@Nonnegative int index) {
        // The last piece may be shorter than the torrent's global piece
        // length. Let's make sure we get the right piece length in any
        // situation.
        if (index < getPieceCount() - 1)
            return getPieceLength();
        return (int) (getSize() % getPieceLength());
    }

    @Nonnull
    @SuppressWarnings("EI_EXPOSE_REP")
    public byte[] getPiecesHashes() {
        return piecesHashes;
    }

    @Nonnull
    public byte[] getPieceHash(@Nonnegative int index) {
        byte[] hashes = getPiecesHashes();
        int offset = index * PIECE_HASH_SIZE;
        return Arrays.copyOfRange(hashes, offset, offset + PIECE_HASH_SIZE);
    }

    public boolean isPieceValid(@Nonnegative int index, @Nonnull ByteBuffer data) {
        if (data.remaining() != getPieceLength(index))
            throw new IllegalArgumentException("Validating piece " + index + " expected " + getPieceLength(index) + ", not " + data.remaining());
        MessageDigest digest = DigestUtils.getSha1Digest();
        digest.update(data);
        return Arrays.equals(digest.digest(), getPieceHash(index));
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
    @Nonnull
    @SuppressWarnings("EI_EXPOSE_REP")
    public byte[] getInfoHash() {
        return this.info_hash;
    }

    /**
     * Get this torrent's info hash (as an hexadecimal-coded string).
     */
    @Nonnull
    public String getHexInfoHash() {
        return TorrentUtils.toHex(this.info_hash);
    }

    /**
     * Return the trackers for this torrent.
     */
    @Nonnull
    public List<List<URI>> getAnnounceList() {
        return this.trackers;
    }

    /**
     * Returns the number of trackers for this torrent.
     */
    @Nonnegative
    public int getTrackerCount() {
        int count = 0;
        for (List<URI> tier : getAnnounceList())
            count += tier.size();
        return count;
    }

    @Nonnull
    public byte[] toByteArray() throws IOException {
        BytesBEncoder encoder = new BytesBEncoder();
        encoder.bencode(decoded);
        return encoder.toByteArray();
    }

    /**
     * Save this torrent meta-info structure into a .torrent file.
     *
     * @param output The stream to write to.
     * @throws IOException If an I/O error occurs while writing the file.
     */
    public void save(@Nonnull @WillNotClose OutputStream output) throws IOException {
        StreamBEncoder encoder = new StreamBEncoder(output);
        encoder.bencode(decoded);
        output.flush();
    }

    /**
     * Return a human-readable representation of this torrent object.
     *
     * <p>
     * The torrent's name is used.
     * </p>
     */
    @Override
    public String toString() {
        return getName();
    }

    @Nonnull
    public static byte[] hash(@Nonnull byte[] data) {
        return DigestUtils.sha1(data);
    }
}