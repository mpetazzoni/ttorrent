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

import com.turn.ttorrent.common.Torrent;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.Callable;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;
import org.apache.commons.codec.digest.DigestUtils;
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
public class Piece {

    private static final Logger logger = LoggerFactory.getLogger(Piece.class);
    private final Torrent torrent;
    private final int index;
    // Piece is considered invalid until first check.
    private volatile boolean valid = false;
    // Piece start unseen
    private final AtomicInteger availability = new AtomicInteger(0);
    // private ByteBuffer data = null;

    /**
     * Initialize a new piece in the byte bucket.
     *
     * @param bucket The underlying byte storage bucket.
     * @param index This piece index in the torrent.
     */
    public Piece(@Nonnull Torrent torrent, @Nonnegative int index) {
        this.torrent = torrent;
        this.index = index;
    }

    @Nonnull
    public Torrent getTorrent() {
        return torrent;
    }

    /**
     * Returns the index of this piece in the torrent.
     */
    @Nonnegative
    public int getIndex() {
        return this.index;
    }

    /**
     * Returns the offset of this piece in the overall data.
     *
     * This is not the same as a block offset.
     */
    @Nonnegative
    public long getOffset() {
        return (long) getIndex() * (long) torrent.getPieceLength();
    }

    /**
     * Try to use {@link Torrent#getPieceLength(int)} or
     * {@link SharedTorrent#getPieceLength(int)} instead of this.
     *
     * @see Torrent#getPieceLength(int)
     */
    @Nonnegative
    public int getLength() {
        return torrent.getPieceLength(getIndex());
    }

    @Nonnull
    public byte[] getHash() {
        byte[] hashes = torrent.getPiecesHashes();
        int offset = getIndex() * Torrent.PIECE_HASH_SIZE;
        return Arrays.copyOfRange(hashes, offset, offset + Torrent.PIECE_HASH_SIZE);
    }

    /**
     * Tells whether this piece's data is valid or not.
     */
    public boolean isValid() {
        return this.valid;
    }

    public void setValid(boolean valid) {
        this.valid = valid;
    }

    /**
     * Mark this piece as being seen at the given peer.
     *
     * @param peer The sharing peer this piece has been seen available at.
     */
    @Nonnegative
    public int seenAt(@Nonnull SharingPeer peer) {
        return availability.incrementAndGet();
    }

    /**
     * Mark this piece as no longer being available at the given peer.
     *
     * @param peer The sharing peer from which the piece is no longer available.
     */
    @Nonnegative
    public int noLongerAt(@Nonnull SharingPeer peer) {
        for (;;) {
            int current = availability.get();
            if (current <= 0)
                return 0;
            int next = current - 1;
            if (availability.compareAndSet(current, next))
                return next;
        }
    }

    @Nonnegative
    public int getAvailability() {
        return availability.get();
    }

    /**
     * Tells whether this piece is available in the current connected peer swarm.
     */
    public boolean isAvailable() {
        return getAvailability() > 0;
    }

    /**
     * Validates this piece.
     *
     * @return Returns true if this piece, as stored in the given byte
     * buffer, is valid, i.e. its SHA1 sum matches the one from the torrent
     * meta-info.
     * 
     * This method allows the caller to use already in-memory data, rather
     * than rereading the underlying storage, and reading sequential data,
     * rather than risking a threadpool randomizing reads.
     */
    public boolean isValid(@Nonnull ByteBuffer data) {
        logger.trace("Validating data for {}...", this);
        MessageDigest digest = DigestUtils.getSha1Digest();
        digest.update(data);
        return Arrays.equals(digest.digest(), getHash());
    }

    /**
     * Return a human-readable representation of this piece.
     */
    @Override
    public String toString() {
        return String.format("piece#%4d%s",
                getIndex(),
                isValid() ? "+" : "-");
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
    public static class Validator implements Runnable {

        private final Piece piece;
        private final ByteBuffer data;
        private final CountDownLatch latch;

        public Validator(Piece piece, ByteBuffer data, CountDownLatch latch) {
            this.piece = piece;
            this.data = data;
            this.latch = latch;
        }

        @Override
        public void run() {
            try {
                if (piece.isValid(data))
                    piece.setValid(true);
            } catch (Exception e) {
                logger.error("Failed validation of " + this, e);
            } finally {
                latch.countDown();
            }
        }
    }
}
