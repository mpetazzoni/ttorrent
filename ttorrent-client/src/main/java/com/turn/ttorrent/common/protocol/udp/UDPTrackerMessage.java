/**
 * Copyright (C) 2012 Turn, Inc.
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
package com.turn.ttorrent.common.protocol.udp;

import com.turn.ttorrent.common.protocol.TrackerMessage;

import io.netty.buffer.ByteBuf;
import javax.annotation.CheckForSigned;
import javax.annotation.Nonnull;

/**
 * Base class for UDP tracker messages.
 *
 * @author mpetazzoni
 */
public abstract class UDPTrackerMessage extends TrackerMessage {

    /**
     * Message type.
     */
    public enum Type {

        UNKNOWN(-1),
        CONNECT_REQUEST(0),
        CONNECT_RESPONSE(0),
        ANNOUNCE_REQUEST(1),
        ANNOUNCE_RESPONSE(1),
        SCRAPE_REQUEST(2),
        SCRAPE_RESPONSE(2),
        ERROR(3);
        // This is the ActionId for the UDP protocol. Do not screw with it.
        private final int id;

        Type(int id) {
            this.id = id;
        }

        public int getId() {
            return this.id;
        }
    };
    private final Type type;
    private int transactionId;

    private UDPTrackerMessage(Type type) {
        this.type = type;
    }

    /**
     * Returns the type of this tracker message.
     */
    @Nonnull
    public Type getType() {
        return type;
    }

    public final int getActionId() {
        return getType().getId();
    }

    public final int getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(int transactionId) {
        this.transactionId = transactionId;
    }

    public abstract void fromWire(@Nonnull ByteBuf in)
            throws MessageValidationException;

    public abstract void toWire(@Nonnull ByteBuf out);

    protected void _fromWire(@Nonnull ByteBuf in, @CheckForSigned int length)
            throws MessageValidationException {
        if (length != -1)
            if (in.readableBytes() != length)
                throw new MessageValidationException("Packet data had bad length: " + in.readableBytes() + "; expected " + length);
    }

    public static abstract class UDPTrackerRequestMessage
            extends UDPTrackerMessage {

        private static final int UDP_MIN_REQUEST_PACKET_SIZE = 16;
        private long connectionId;

        protected UDPTrackerRequestMessage(@Nonnull Type type) {
            super(type);
        }

        public final long getConnectionId() {
            return connectionId;
        }

        public void setConnectionId(long connectionId) {
            this.connectionId = connectionId;
        }

        protected void _toWire(@Nonnull ByteBuf out) {
            out.writeLong(getConnectionId());
            out.writeInt(getActionId());
            out.writeInt(getTransactionId());
        }

        @Override
        protected void _fromWire(@Nonnull ByteBuf in, @CheckForSigned int length)
                throws MessageValidationException {
            super._fromWire(in, length);
            setConnectionId(in.readLong());
            int actionId = in.readInt();
            if (actionId != getActionId())
                throw new MessageValidationException("Packet contained bad ActionId: " + this);
            setTransactionId(in.readInt());
        }

        public static UDPTrackerRequestMessage parse(ByteBuf data)
                throws MessageValidationException {
            if (data.readableBytes() < UDP_MIN_REQUEST_PACKET_SIZE) {
                throw new MessageValidationException("Invalid packet size!");
            }

            /**
             * UDP request packets always start with the connection ID (8 bytes),
             * followed by the action (4 bytes). Extract the action code
             * accordingly.
             */
            int action = data.getInt(8);

            if (action == Type.CONNECT_REQUEST.getId()) {
                return UDPConnectRequestMessage.parse(data);
            } else if (action == Type.ANNOUNCE_REQUEST.getId()) {
                return UDPAnnounceRequestMessage.parse(data);
            }

            throw new MessageValidationException("Unknown UDP tracker "
                    + "request message!");
        }
    };

    public static abstract class UDPTrackerResponseMessage
            extends UDPTrackerMessage {

        private static final int UDP_MIN_RESPONSE_PACKET_SIZE = 8;

        protected UDPTrackerResponseMessage(Type type) {
            super(type);
        }

        @Override
        protected void _fromWire(@Nonnull ByteBuf in, @CheckForSigned int length)
                throws MessageValidationException {
            super._fromWire(in, length);
            int actionId = in.readInt();
            if (actionId != getActionId())
                throw new MessageValidationException("Packet contained bad ActionId: " + this);
            setTransactionId(in.readInt());
        }

        protected void _toWire(@Nonnull ByteBuf out) {
            out.writeInt(getActionId());
            out.writeInt(getTransactionId());
        }

        public static UDPTrackerResponseMessage parse(ByteBuf data)
                throws MessageValidationException {
            if (data.readableBytes() < UDP_MIN_RESPONSE_PACKET_SIZE) {
                throw new MessageValidationException("Invalid packet size!");
            }

            /**
             * UDP response packets always start with the action (4 bytes), so
             * we can extract it immediately.
             */
            int action = data.getInt(0);

            if (action == Type.CONNECT_RESPONSE.getId()) {
                return UDPConnectResponseMessage.parse(data);
            } else if (action == Type.ANNOUNCE_RESPONSE.getId()) {
                return UDPAnnounceResponseMessage.parse(data);
            } else if (action == Type.ERROR.getId()) {
                return UDPTrackerErrorMessage.parse(data);
            }

            throw new MessageValidationException("Unknown UDP tracker "
                    + "response message!");
        }
    };
}
