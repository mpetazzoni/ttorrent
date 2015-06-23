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
package com.turn.ttorrent.client.io;

import com.turn.ttorrent.client.io.PeerExtendedMessage.ExtendedType;
import com.turn.ttorrent.protocol.TorrentUtils;
import com.turn.ttorrent.protocol.bcodec.BEUtils;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.netty.buffer.ByteBuf;
import java.util.Arrays;
import java.util.Map;
import javax.annotation.Nonnull;
import org.apache.commons.io.Charsets;

/**
 *
 * @author shevek
 */
public class PeerHandshakeMessage extends PeerMessage {

    public static enum Feature {

        BEP10_EXTENSION_PROTOCOL {
            @Override
            public boolean get(byte[] reserved) {
                return (reserved[5] & 0x10) != 0;
            }

            @Override
            public void set(byte[] reserved) {
                reserved[5] |= 0x10;
            }
        };

        public abstract boolean get(@Nonnull byte[] reserved);

        public abstract void set(@Nonnull byte[] reserved);
    }
    public static final int BASE_HANDSHAKE_LENGTH = 49;
    private static final byte[] BITTORRENT_PROTOCOL_IDENTIFIER = "BitTorrent protocol".getBytes(BEUtils.BYTE_ENCODING);
    private byte[] protocolName;
    private final byte[] reserved = new byte[8];
    private byte[] infoHash; // 20
    private byte[] peerId; // 20

    public PeerHandshakeMessage() {
        Feature.BEP10_EXTENSION_PROTOCOL.set(reserved); // Overwritten by fromWire()
    }

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public PeerHandshakeMessage(@Nonnull byte[] infoHash, @Nonnull byte[] peerId) {
        this();
        if (infoHash.length != 20)
            throw new IllegalArgumentException("InfoHash length should be 20, not " + infoHash.length);
        if (peerId.length != 20)
            throw new IllegalArgumentException("PeerId length should be 20, not " + peerId.length);
        this.protocolName = BITTORRENT_PROTOCOL_IDENTIFIER;
        this.infoHash = infoHash;
        this.peerId = peerId;
    }

    @Override
    public Type getType() {
        return Type.HANDSHAKE;
    }

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public byte[] getInfoHash() {
        return infoHash;
    }

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public byte[] getPeerId() {
        return peerId;
    }

    @Nonnull
    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public byte[] getReserved() {
        return reserved;
    }

    @Override
    public void fromWire(ByteBuf in) {
        int pstrlen = in.readUnsignedByte();
        if (pstrlen < 0 || in.readableBytes() < BASE_HANDSHAKE_LENGTH + pstrlen - 1)
            throw new IllegalArgumentException("Incorrect handshake message length (pstrlen=" + pstrlen + ") !");

        // Check the protocol identification string
        protocolName = new byte[pstrlen];
        in.readBytes(protocolName);
        if (!Arrays.equals(protocolName, BITTORRENT_PROTOCOL_IDENTIFIER))
            throw new IllegalArgumentException("Unknown protocol " + new String(protocolName, Charsets.ISO_8859_1));

        // Ignore reserved bytes
        in.readBytes(reserved);

        infoHash = new byte[20];
        in.readBytes(infoHash);
        peerId = new byte[20];
        in.readBytes(peerId);
    }

    public void toWire(@Nonnull ByteBuf out) {
        out.writeByte(protocolName.length);
        out.writeBytes(protocolName);
        out.writeBytes(reserved);
        out.writeBytes(infoHash);
        out.writeBytes(peerId);
    }

    @Override
    public void toWire(ByteBuf out, Map<? extends ExtendedType, ? extends Byte> extendedTypes) {
        throw new UnsupportedOperationException("Message should not appear in normal protocol.");
    }

    @Override
    public String toString() {
        return super.toString() + " P=" + TorrentUtils.toTextOrNull(getPeerId()) + " T=" + TorrentUtils.toHexOrNull(getInfoHash());
    }
}
