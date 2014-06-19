/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.client.io;

import com.turn.ttorrent.bcodec.BEValue;
import com.turn.ttorrent.bcodec.NettyBDecoder;
import com.turn.ttorrent.bcodec.NettyBEncoder;
import io.netty.buffer.ByteBuf;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nonnull;

/**
 *
 * @author shevek
 */
public abstract class PeerExtendedMessage extends PeerMessage {

    public static enum ExtendedType {

        // Must be ordinal zero.
        handshake,
        ut_pex;
    }

    @Override
    public Type getType() {
        return Type.EXTENDED;
    }

    @Nonnull
    public abstract ExtendedType getExtendedType();

    @Override
    public void toWire(ByteBuf out) throws IOException {
        super.toWire(out);
        Map<String, Byte> remoteTypes = null;   // TODO
        Byte remoteType = remoteTypes.get(getExtendedType().name());
        out.writeByte(remoteType);
    }

    public static class HandshakeMessage extends PeerExtendedMessage {

        private byte[] remoteIp4;
        private byte[] remoteIp6;
        private int remotePort;
        private String remoteVersion;
        private byte[] localIp;
        private int requestQueueLength;

        @Override
        public ExtendedType getExtendedType() {
            return ExtendedType.handshake;
        }

        @Override
        public void fromWire(ByteBuf in) throws IOException {
            NettyBDecoder decoder = new NettyBDecoder(in);
            Map<String, BEValue> payload = decoder.bdecodeMap().getMap();
            // TODO: This may not be present.
            remotePort = payload.get("p").getInt();
        }

        @Override
        public void toWire(ByteBuf out) throws IOException {
            NettyBEncoder encoder = new NettyBEncoder(out);
            Map<String, BEValue> payload = new HashMap<String, BEValue>();
            // TODO: etc.
            encoder.bencode(payload);
        }
    }
}
