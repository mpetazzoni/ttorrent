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
package com.turn.ttorrent.client.io;

import com.turn.ttorrent.client.TorrentHandler;
import io.netty.buffer.ByteBuf;
import java.nio.ByteBuffer;
import java.text.ParseException;
import java.util.BitSet;
import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;

/**
 * BitTorrent peer protocol messages representations.
 *
 * <p>
 * This class and its <em>*Messages</em> subclasses provide POJO
 * representations of the peer protocol messages, along with easy parsing from
 * an input ByteBuffer to quickly get a usable representation of an incoming
 * message.
 * </p>
 *
 * @author mpetazzoni
 * @see <a href="http://wiki.theory.org/BitTorrentSpecification#Peer_wire_protocol_.28TCP.29">BitTorrent peer wire protocol</a>
 */
public abstract class PeerMessage {

    /** The size, in bytes, of the length field in a message (one 32-bit
     * integer). */
    public static final int MESSAGE_LENGTH_FIELD_SIZE = 4;

    /**
     * Message type.
     *
     * <p>
     * Note that the keep-alive messages don't actually have an type ID defined
     * in the protocol as they are of length 0.
     * </p>
     */
    public enum Type {

        HANDSHAKE(-2),
        KEEP_ALIVE(-1),
        CHOKE(0),
        UNCHOKE(1),
        INTERESTED(2),
        NOT_INTERESTED(3),
        HAVE(4),
        BITFIELD(5),
        REQUEST(6),
        PIECE(7),
        CANCEL(8);
        private byte id;

        Type(int id) {
            this.id = (byte) id;
        }

        public byte getTypeByte() {
            return this.id;
        }

        public static Type get(byte c) {
            for (Type t : Type.values()) {
                if (t.getTypeByte() == c) {
                    return t;
                }
            }
            return null;
        }
    };

    public abstract Type getType();

    /** Reads everything except the length and type code. */
    public abstract void fromWire(ByteBuf in);

    /** Writes everything except the length. */
    public void toWire(ByteBuf out) {
        out.writeByte(getType().getTypeByte());
    }

    /**
     * Validate that this message makes sense for the torrent it's related to.
     *
     * <p>
     * This method is meant to be overloaded by distinct message types, where
     * it makes sense. Otherwise, it defaults to true.
     * </p>
     *
     * @param torrent The torrent this message is about.
     */
    public PeerMessage validate(TorrentHandler torrent)
            throws MessageValidationException {
        return this;
    }

    @Override
    public String toString() {
        return this.getType().name();
    }

    public static class MessageValidationException extends ParseException {

        static final long serialVersionUID = -1;

        public MessageValidationException(PeerMessage m) {
            super("Message " + m + " is not valid!", 0);
        }
    }

    /**
     * Keep alive message.
     *
     * <len=0000>
     */
    public static class KeepAliveMessage extends PeerMessage {

        @Override
        public Type getType() {
            return Type.KEEP_ALIVE;
        }

        @Override
        public void fromWire(ByteBuf in) {
        }

        @Override
        public void toWire(ByteBuf out) {
        }
    }

    /**
     * Choke message.
     *
     * <len=0001><id=0>
     */
    public static class ChokeMessage extends PeerMessage {

        @Override
        public Type getType() {
            return Type.CHOKE;
        }

        @Override
        public void fromWire(ByteBuf in) {
        }
    }

    /**
     * Unchoke message.
     *
     * <len=0001><id=1>
     */
    public static class UnchokeMessage extends PeerMessage {

        @Override
        public Type getType() {
            return Type.UNCHOKE;
        }

        @Override
        public void fromWire(ByteBuf in) {
        }
    }

    /**
     * Interested message.
     *
     * <len=0001><id=2>
     */
    public static class InterestedMessage extends PeerMessage {

        @Override
        public Type getType() {
            return Type.INTERESTED;
        }

        @Override
        public void fromWire(ByteBuf in) {
        }
    }

    /**
     * Not interested message.
     *
     * <len=0001><id=3>
     */
    public static class NotInterestedMessage extends PeerMessage {

        @Override
        public Type getType() {
            return Type.NOT_INTERESTED;
        }

        @Override
        public void fromWire(ByteBuf in) {
        }
    }

    /**
     * Have message.
     *
     * <len=0005><id=4><piece index=xxxx>
     */
    public static class HaveMessage extends PeerMessage {

        private int piece;

        public HaveMessage() {
        }

        public HaveMessage(@Nonnegative int piece) {
            this.piece = piece;
        }

        @Override
        public Type getType() {
            return Type.HAVE;
        }

        @Nonnegative
        public int getPiece() {
            return this.piece;
        }

        @Override
        public void fromWire(ByteBuf in) {
            piece = in.readInt();
        }

        @Override
        public void toWire(ByteBuf out) {
            super.toWire(out);
            out.writeInt(piece);
        }

        @Override
        public HaveMessage validate(TorrentHandler torrent)
                throws MessageValidationException {
            if (this.piece >= 0 && this.piece < torrent.getPieceCount())
                return this;
            throw new MessageValidationException(this);
        }

        @Override
        public String toString() {
            return super.toString() + " #" + this.getPiece();
        }
    }

    /**
     * Bitfield message.
     *
     * <len=0001+X><id=5><bitfield>
     */
    public static class BitfieldMessage extends PeerMessage {

        private BitSet bitfield;

        public BitfieldMessage() {
        }

        public BitfieldMessage(@Nonnull BitSet bitfield) {
            this.bitfield = bitfield;
        }

        @Override
        public Type getType() {
            return Type.BITFIELD;
        }

        public static byte reverse(byte b) {
            int i = b & 0xFF;
            i = (i & 0x55) << 1 | (i >>> 1) & 0x55;
            i = (i & 0x33) << 2 | (i >>> 2) & 0x33;
            i = (i & 0x0f) << 4 | (i >>> 4) & 0x0f;
            return (byte) i;
        }

        @Nonnull
        public BitSet getBitfield() {
            return this.bitfield;
        }

        @Override
        public void fromWire(ByteBuf in) {
            byte[] bytes = new byte[in.readableBytes()];
            in.readBytes(bytes);
            for (int i = 0; i < bytes.length; i++)
                bytes[i] = reverse(bytes[i]);
            bitfield = BitSet.valueOf(bytes);
        }

        @Override
        public void toWire(ByteBuf out) {
            super.toWire(out);
            byte[] bytes = bitfield.toByteArray();
            for (int i = 0; i < bytes.length; i++)
                bytes[i] = reverse(bytes[i]);
            out.writeBytes(bytes);
        }

        @Override
        public BitfieldMessage validate(TorrentHandler torrent)
                throws MessageValidationException {
            if (this.bitfield.length() > torrent.getPieceCount())
                throw new MessageValidationException(this);
            return this;
        }

        @Override
        public String toString() {
            return super.toString() + " " + this.getBitfield().cardinality();
        }
    }

    public static abstract class AbstractPieceMessage extends PeerMessage {

        private int piece;
        private int offset;

        public AbstractPieceMessage() {
        }

        public AbstractPieceMessage(int piece, int offset) {
            this.piece = piece;
            this.offset = offset;
        }

        @Nonnegative
        public int getPiece() {
            return this.piece;
        }

        @Nonnegative
        public int getOffset() {
            return this.offset;
        }

        @Nonnegative
        public abstract int getLength();

        @Override
        public void fromWire(ByteBuf in) {
            piece = in.readInt();
            offset = in.readInt();
        }

        @Override
        public void toWire(ByteBuf out) {
            super.toWire(out);
            out.writeInt(piece);
            out.writeInt(offset);
        }

        @Override
        public AbstractPieceMessage validate(TorrentHandler torrent)
                throws MessageValidationException {
            if (getPiece() < 0)
                throw new MessageValidationException(this);
            if (getPiece() > torrent.getPieceCount())
                throw new MessageValidationException(this);
            if (getOffset() + getLength() > torrent.getPieceLength(piece))
                throw new MessageValidationException(this);
            return this;
        }

        public boolean answers(@Nonnull AbstractPieceMessage message) {
            return getPiece() == message.getPiece()
                    && getOffset() == message.getOffset()
                    && getLength() == message.getLength();
        }

        @Override
        public String toString() {
            return super.toString() + " #" + this.getPiece()
                    + " (" + this.getLength() + "@" + this.getOffset() + ")";
        }
    }

    /**
     * Request message.
     *
     * <len=00013><id=6><piece index><block offset><block length>
     */
    public static class RequestMessage extends AbstractPieceMessage {

        private int length;

        public RequestMessage() {
        }

        public RequestMessage(@Nonnegative int piece, @Nonnegative int offset, @Nonnegative int length) {
            super(piece, offset);
            this.length = length;
        }

        @Override
        public Type getType() {
            return Type.REQUEST;
        }

        @Override
        public int getLength() {
            return length;
        }

        @Override
        public void fromWire(ByteBuf in) {
            super.fromWire(in);
            length = in.readInt();
        }

        @Override
        public void toWire(ByteBuf out) {
            super.toWire(out);
            out.writeInt(length);
        }
    }

    /**
     * Piece message.
     *
     * <len=0009+X><id=7><piece index><block offset><block data>
     */
    public static class PieceMessage extends AbstractPieceMessage {

        private static final int BASE_SIZE = 9;
        // TODO: Use a FileRegion.
        private ByteBuffer block;

        public PieceMessage() {
        }

        public PieceMessage(int piece, int offset, ByteBuffer block) {
            super(piece, offset);
            this.block = block;
        }

        @Override
        public Type getType() {
            return Type.PIECE;
        }

        @Override
        public int getLength() {
            return getBlock().remaining();
        }

        public ByteBuffer getBlock() {
            return this.block;
        }

        @Override
        public void fromWire(ByteBuf in) {
            super.fromWire(in);
            block = ByteBuffer.allocate(in.readableBytes());
            in.readBytes(block);
            block.flip();
            // We can't do this because netty recycles the buffer.
            // block = in.nioBuffer();
            // in.readerIndex(in.writerIndex());
        }

        @Override
        public void toWire(ByteBuf out) {
            super.toWire(out);
            out.writeBytes(block);
        }
    }

    /**
     * Cancel message.
     *
     * <len=00013><id=8><piece index><block offset><block length>
     */
    public static class CancelMessage extends AbstractPieceMessage {

        private int length;

        public CancelMessage() {
        }

        public CancelMessage(@Nonnegative int piece, @Nonnegative int offset, @Nonnegative int length) {
            super(piece, offset);
            this.length = length;
        }

        public CancelMessage(@Nonnull RequestMessage request) {
            this(request.getPiece(), request.getOffset(), request.getLength());
        }

        @Override
        public Type getType() {
            return Type.CANCEL;
        }

        @Override
        public int getLength() {
            return length;
        }

        @Override
        public void fromWire(ByteBuf in) {
            super.fromWire(in);
            length = in.readInt();
        }

        @Override
        public void toWire(ByteBuf out) {
            super.toWire(out);
            out.writeInt(length);
        }
    }
}
