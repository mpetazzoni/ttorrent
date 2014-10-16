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
import com.google.common.collect.UnmodifiableIterator;
import com.turn.ttorrent.client.PeerPieceProvider;
import com.turn.ttorrent.client.io.PeerMessage;
import com.turn.ttorrent.client.peer.PieceHandler.AnswerableRequestMessage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.BitSet;
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
public class PieceHandler implements Iterable<AnswerableRequestMessage> {

    private static final Logger LOG = LoggerFactory.getLogger(PieceHandler.class);
    /** Default block size is 2^14 bytes, or 16kB. */
    public static final int DEFAULT_BLOCK_SIZE = 16384;
    /** Max block request size is 2^17 bytes, or 131kB. */
    public static final int MAX_BLOCK_SIZE = 128 * 1024;   // This is 131072 in most implementations.
    private final int piece;
    private final PeerPieceProvider provider;
    // TODO: Maintain the set of peers which sent us data, so we can bin bad peers.
    @GuardedBy("lock")
    private final byte[] pieceData;
    @GuardedBy("lock")
    private final BitSet pieceRequiredBytes;    // We should do this with blocks.
    private final Object lock = new Object();

    public PieceHandler(@Nonnull PeerPieceProvider provider, @Nonnegative int piece) {
        this.provider = provider;
        this.piece = piece;
        this.pieceData = new byte[provider.getPieceLength(piece)];
        this.pieceRequiredBytes = new BitSet(pieceData.length);
        this.pieceRequiredBytes.set(0, pieceData.length);  // It's easier to find 1s than 0s.
    }

    /**
     * Returns the index of this piece in the torrent.
     */
    @Nonnegative
    public int getIndex() {
        return piece;
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
        // LOG.debug("Received {}[{}]", offset, length);

        synchronized (lock) {
            // Make sure we actually needed any of these bytes.
            if (provider.isCompletedPiece(piece)) {
                if (LOG.isDebugEnabled())
                    LOG.debug("{}: Discarding block of non-required piece {}", provider.getLocalPeerName(), piece);
                return Reception.IGNORED;
            }
            if (pieceRequiredBytes.nextSetBit(offset) >= offset + length) {
                if (LOG.isDebugEnabled())
                    LOG.debug("{}: Discarding non-required block for {}", provider.getLocalPeerName(), piece);
                return Reception.IGNORED;
            }

            block.get(pieceData, offset, length);
            pieceRequiredBytes.clear(offset, offset + length);

            if (!pieceRequiredBytes.isEmpty())
                return Reception.INCOMPLETE;

            boolean valid = provider.validateBlock(ByteBuffer.wrap(pieceData), piece);
            if (!valid) {
                // LOG.warn("{}: Piece {} complete, but invalid. Not saving.", new Object[]{provider.getLocalPeerName(), piece});
                this.pieceRequiredBytes.set(0, pieceData.length);
                return Reception.INVALID;
            }
        }

        // if (LOG.isDebugEnabled())
        // LOG.debug("Piece {} complete, and valid.", piece);
        provider.writeBlock(ByteBuffer.wrap(pieceData), piece, 0);
        return Reception.VALID;
    }

    public class AnswerableRequestMessage extends PeerMessage.RequestMessage {

        // This is written before PeerHandler.requestsSent and read afterwards.
        private long requestTime = -1;

        public AnswerableRequestMessage(int piece, int offset, int length) {
            super(piece, offset, length);
        }

        @Nonnull
        public PieceHandler getPieceHandler() {
            return PieceHandler.this;
        }

        public long getRequestTime() {
            return requestTime;
        }

        public void setRequestTime() {
            requestTime = System.currentTimeMillis();
        }

        // TODO: Make public, and call when appropriate.
        private void cancel() {
            // TODO: Add to partial set.
        }

        @Nonnull
        public Reception answer(@Nonnull PeerMessage.PieceMessage response) throws IOException {
            if (!response.answers(this))
                throw new IllegalArgumentException("Not an answer: request=" + this + ", response=" + response);
            return receive(response.getBlock(), response.getOffset());
        }

        @Override
        public int hashCode() {
            return PieceHandler.this.hashCode() << 16 ^ getOffset() << 8 ^ getLength();
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (null == obj)
                return false;
            if (!getClass().equals(obj.getClass()))
                return false;
            AnswerableRequestMessage other = (AnswerableRequestMessage) obj;
            return getPieceHandler() == other.getPieceHandler()
                    && getOffset() == other.getOffset()
                    && getLength() == other.getLength();
        }

        @Override
        public String toString() {
            long offset = System.currentTimeMillis() - getRequestTime();
            return super.toString() + " (" + offset + " ms ago)";
        }
    }
    private static final int REQUEST_OFFSET_INIT = -1;
    private static final int REQUEST_OFFSET_FINI = -2;

    private class AnswerableRequestIterator extends AbstractIterator<AnswerableRequestMessage> {

        private int requestOffset = REQUEST_OFFSET_INIT;

        @Override
        protected AnswerableRequestMessage computeNext() {
            int blockLength = provider.getBlockLength();
            synchronized (lock) {
                if (requestOffset == REQUEST_OFFSET_FINI)
                    return endOfData();
                else if (requestOffset == REQUEST_OFFSET_INIT)
                    requestOffset = pieceRequiredBytes.nextSetBit(0);
                else
                    requestOffset = pieceRequiredBytes.nextSetBit(requestOffset + blockLength);
                if (requestOffset < 0) {
                    requestOffset = REQUEST_OFFSET_FINI;
                    return endOfData();
                }
                int length = Math.min(
                        blockLength,
                        pieceData.length - requestOffset);
                return new AnswerableRequestMessage(piece, requestOffset, length);
            }
        }
    }

    @Override
    public UnmodifiableIterator<AnswerableRequestMessage> iterator() {
        return new AnswerableRequestIterator();
    }

    @Override
    public String toString() {
        return "PieceHandler(" + piece + ")";
    }
}