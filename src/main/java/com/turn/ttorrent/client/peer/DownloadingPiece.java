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

import com.turn.ttorrent.client.Piece;
import com.turn.ttorrent.client.SharedTorrent;
import com.turn.ttorrent.client.io.PeerMessage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.BitSet;
import javax.annotation.CheckForNull;
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
    @GuardedBy("lock")
    private final byte[] pieceData;
    @GuardedBy("lock")
    private final BitSet pieceRequiredBytes;    // We could do this with blocks, but why bother?
    private int requestOffset = -1;
    private final Object lock = new Object();

    public DownloadingPiece(@Nonnull Piece piece) {
        this.piece = piece;
        this.pieceData = new byte[piece.getLength()];
        this.pieceRequiredBytes = new BitSet(pieceData.length);
        this.pieceRequiredBytes.set(0, pieceData.length);  // It's easier to find 1s than 0s.
    }

    @Nonnull
    private SharedTorrent getTorrent() {
        return piece.getTorrent();
    }

    /**
     * Returns the index of this piece in the torrent.
     */
    @Nonnegative
    public int getIndex() {
        return piece.getIndex();
    }

    public static enum Reception {

        IGNORED, INCOMPLETE, VALID, INVALID;
    }

    /**
     * Record the given block at the given offset in this piece.
     *
     * @param block The ByteBuffer containing the block data.
     * @param offset The block offset in this piece.
     */
    @Nonnull
    private Reception receive(ByteBuffer block, int offset) throws IOException {
        int length = block.remaining();

        synchronized (lock) {
            // Make sure we actually needed any of these bytes.
            if (pieceRequiredBytes.nextSetBit(offset) >= offset + length) {
                if (logger.isDebugEnabled())
                    logger.debug("Discarding non-required block for " + piece);
                return Reception.IGNORED;
            }

            block.get(pieceData, offset, length);
            pieceRequiredBytes.clear(offset, offset + length);

            if (!pieceRequiredBytes.isEmpty())
                return Reception.INCOMPLETE;

            boolean valid = piece.isValid(ByteBuffer.wrap(pieceData));
            if (!valid) {
                logger.warn("Piece {} complete, but invalid. Not saving.", piece);
                this.pieceRequiredBytes.set(0, piece.getLength());
                return Reception.INVALID;
            }
        }

        if (logger.isDebugEnabled())
            logger.debug("Piece {} complete, and valid.", piece);
        getTorrent().getBucket().write(ByteBuffer.wrap(pieceData), piece.getOffset());
        piece.setValid(true);
        return Reception.VALID;
    }

    public class AnswerableRequestMessage extends PeerMessage.RequestMessage {

        private final long requestTime = System.currentTimeMillis();

        public AnswerableRequestMessage(int piece, int offset, int length) {
            super(piece, offset, length);
        }

        @Nonnull
        public DownloadingPiece getDownloadingPiece() {
            return DownloadingPiece.this;
        }

        public long getRequestTime() {
            return requestTime;
        }

        public Reception answer(PeerMessage.PieceMessage response) throws IOException {
            if (!response.answers(this))
                throw new IllegalArgumentException("Not an answer: request=" + this + ", response=" + response);
            return receive(response.getBlock(), response.getOffset());
        }
    }

    /** Returns null once when all blocks have been requested, then cycles. */
    @CheckForNull
    public AnswerableRequestMessage nextRequest() {
        int blockSize = getTorrent().getBlockSize();
        synchronized (lock) {
            if (requestOffset < 0)
                requestOffset = pieceRequiredBytes.nextSetBit(0);
            else
                requestOffset = pieceRequiredBytes.nextSetBit(requestOffset + blockSize);
            if (requestOffset < 0)
                return null;
            int length = Math.min(
                    blockSize,
                    piece.getLength() - requestOffset);
            return new AnswerableRequestMessage(piece.getIndex(), requestOffset, length);
        }
    }
}