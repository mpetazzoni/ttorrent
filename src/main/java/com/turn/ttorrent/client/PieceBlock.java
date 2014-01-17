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
package com.turn.ttorrent.client;

import com.turn.ttorrent.client.io.PeerMessage;
import io.netty.buffer.ByteBuf;
import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;

/**
 *
 * @author shevek
 */
public class PieceBlock {

    /** Default block size is 2^14 bytes, or 16kB. */
    public static final int DEFAULT_SIZE = 16384;
    /** Max block request size is 2^17 bytes, or 131kB. */
    public static final int MAX_SIZE = 131072;
    private final int piece;
    private final int offset;
    private final int length;

    public PieceBlock(@Nonnegative int piece, @Nonnegative int offset, @Nonnegative int length) {
        this.piece = piece;
        this.offset = offset;
        this.length = length;
    }

    public PieceBlock(@Nonnull PeerMessage.PieceMessage message) {
        this(message.getPiece(), message.getOffset(), message.getLength());
    }

    @Nonnegative
    public int getPiece() {
        return piece;
    }

    @Nonnegative
    public int getOffset() {
        return offset;
    }

    @Nonnegative
    public int getLength() {
        return length;
    }

    @Override
    public int hashCode() {
        return (piece << 5) ^ (offset << 2) ^ length;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (null == obj)
            return false;
        if (!getClass().equals(obj.getClass()))
            return false;
        PieceBlock other = (PieceBlock) obj;
        return piece == other.piece
                && offset == other.offset
                && length == other.length;
    }

    @Override
    public String toString() {
        return "P" + piece + ":" + offset + ".[" + length + "]";
    }
}
