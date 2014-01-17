/*
 * Copyright 2014 shevek.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.turn.ttorrent.client.peer;

import com.google.common.collect.AbstractIterator;
import com.turn.ttorrent.client.Piece;
import com.turn.ttorrent.client.PieceBlock;
import com.turn.ttorrent.client.SharedTorrent;
import com.turn.ttorrent.client.io.PeerMessage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.BitSet;
import java.util.Iterator;
import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;
import javax.annotation.concurrent.GuardedBy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Enables out-of-order reception of blocks within a piece.
 *
 * @author shevek
 */
public class DownloadingPiece {

    private static final Logger logger = LoggerFactory.getLogger(DownloadingPiece.class);
    private final Piece piece;
    private final int blockSize;
    private final BitSet pieceRequiredBytes;    // We could do this with blocks, but why bother?
    private final byte[] pieceData;
    private final Object lock = new Object();

    public DownloadingPiece(@Nonnull Piece piece, @Nonnegative int blockSize) {
        if (blockSize > PieceBlock.MAX_SIZE)
            throw new IllegalArgumentException("Illegal block size " + blockSize + " > max " + PieceBlock.MAX_SIZE);
        this.piece = piece;
        this.blockSize = blockSize;
        this.pieceRequiredBytes = new BitSet(blockSize);
        this.pieceRequiredBytes.set(0, piece.getLength());  // It's easier to find 1s than 0s.
        this.pieceData = new byte[piece.getLength()];
    }

    public DownloadingPiece(@Nonnull Piece piece) {
        this(piece, PieceBlock.DEFAULT_SIZE);
    }

    public SharedTorrent getTorrent() {
        return piece.getTorrent();
    }

    public boolean isComplete() {
        synchronized (lock) {
            return pieceRequiredBytes.isEmpty();
        }
    }

    // I guess I bit the bullet and used Guava. It is SOOO TASTY!
    private class _Iterator extends AbstractIterator<PeerMessage.RequestMessage> {

        @GuardedBy("lock")
        private int offset = -1;

        @Override
        protected PeerMessage.RequestMessage computeNext() {
            synchronized (lock) {
                if (offset < 0)
                    offset = pieceRequiredBytes.nextSetBit(offset);
                else
                    offset = pieceRequiredBytes.nextSetBit(offset + blockSize);
                if (offset < 0)
                    return null;
                int length = Math.min(
                        blockSize,
                        piece.getLength() - offset);
                return new PeerMessage.RequestMessage(piece.getIndex(), offset, length);
            }
        }
    }

    @Nonnull
    public Iterable<PeerMessage.RequestMessage> requests() {
        return new Iterable<PeerMessage.RequestMessage>() {
            @Override
            public Iterator<PeerMessage.RequestMessage> iterator() {
                return new _Iterator();
            }
        };
    }

    /**
     * Record the given block at the given offset in this piece.
     *
     * @param block The ByteBuffer containing the block data.
     * @param offset The block offset in this piece.
     */
    public boolean receive(ByteBuffer block, int offset) throws IOException {
        int length = block.remaining();

        synchronized (lock) {
            // Make sure we actually needed any of these bytes.
            if (pieceRequiredBytes.nextSetBit(offset) >= offset + length) {
                logger.debug("Discarding non-required block for " + piece);
                return false;
            }

            block.get(pieceData, offset, length);
            pieceRequiredBytes.clear(offset, offset + length);

            if (!isComplete())
                return false;

            boolean valid = piece.isValid(ByteBuffer.wrap(pieceData));
            if (!valid) {
                logger.warn("Piece {} complete, but invalid. Not saving.", piece);
                this.pieceRequiredBytes.set(0, piece.getLength());
                return false;
            }
        }

        getTorrent().getBucket().write(ByteBuffer.wrap(pieceData), piece.getOffset());
        piece.setValid(true);
        return true;
    }
}