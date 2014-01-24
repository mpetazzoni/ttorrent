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

import com.turn.ttorrent.client.PeerPieceProvider;
import com.turn.ttorrent.client.Piece;
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
public class PieceHandler {

    private static final Logger logger = LoggerFactory.getLogger(PieceHandler.class);
    /** Default block size is 2^14 bytes, or 16kB. */
    public static final int DEFAULT_BLOCK_SIZE = 16384;
    /** Max block request size is 2^17 bytes, or 131kB. */
    public static final int MAX_BLOCK_SIZE = 131072;
    private final Piece piece;
    private final PeerPieceProvider provider;
    @GuardedBy("lock")
    private final byte[] pieceData;
    @GuardedBy("lock")
    private final BitSet pieceRequiredBytes;    // We could do this with blocks, but why bother?
    private static final int REQUEST_OFFSET_INIT = -1;
    private static final int REQUEST_OFFSET_FINI = -2;
    private int requestOffset = REQUEST_OFFSET_INIT;
    private final Object lock = new Object();

    public PieceHandler(@Nonnull Piece piece, @Nonnull PeerPieceProvider provider) {
        this.piece = piece;
        this.provider = provider;
        this.pieceData = new byte[piece.getLength()];
        this.pieceRequiredBytes = new BitSet(pieceData.length);
        this.pieceRequiredBytes.set(0, pieceData.length);  // It's easier to find 1s than 0s.
    }

    /**
     * Returns the index of this piece in the torrent.
     */
    @Nonnegative
    public int getIndex() {
        return piece.getIndex();
    }

    public static enum Reception {

        /** Corresponding request not found. */
        WAT,
        /** Not required. */
        IGNORED,
        /** Thankyou, but piece not complete. */
        INCOMPLETE,
        /** Thank you, piece complete. */
        VALID,
        /** Thank you, but you sent me a bum piece. */
        INVALID;
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
        provider.writeBlock(ByteBuffer.wrap(pieceData), piece.getIndex(), 0);
        piece.setValid(true);
        return Reception.VALID;
    }

    public class AnswerableRequestMessage extends PeerMessage.RequestMessage {

        private final long requestTime = System.currentTimeMillis();

        public AnswerableRequestMessage(int piece, int offset, int length) {
            super(piece, offset, length);
        }

        @Nonnull
        public PieceHandler getDownloadingPiece() {
            return PieceHandler.this;
        }

        public long getRequestTime() {
            return requestTime;
        }

        // TODO: Make public, and call when appropriate.
        private void cancel() {
            // TODO: Add to partial set.
        }

        @Nonnull
        public Reception answer(PeerMessage.PieceMessage response) throws IOException {
            if (!response.answers(this))
                throw new IllegalArgumentException("Not an answer: request=" + this + ", response=" + response);
            return receive(response.getBlock(), response.getOffset());
        }
    }

    /** Returns null once when all blocks have been requested, then cycles. */
    @CheckForNull
    public AnswerableRequestMessage nextRequest() {
        int blockSize = DEFAULT_BLOCK_SIZE;
        synchronized (lock) {
            if (requestOffset == REQUEST_OFFSET_FINI)
                return null;
            else if (requestOffset == REQUEST_OFFSET_INIT)
                requestOffset = pieceRequiredBytes.nextSetBit(0);
            else
                requestOffset = pieceRequiredBytes.nextSetBit(requestOffset + blockSize);
            if (requestOffset < 0) {
                requestOffset = REQUEST_OFFSET_FINI;
                return null;
            }
            int length = Math.min(
                    blockSize,
                    piece.getLength() - requestOffset);
            return new AnswerableRequestMessage(piece.getIndex(), requestOffset, length);
        }
    }

    @Override
    public String toString() {
        return "PieceHandler(" + piece + ")";
    }
}