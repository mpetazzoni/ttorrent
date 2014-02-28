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

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageCodec;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author shevek
 */
// Not shareable
public class PeerMessageCodec extends ByteToMessageCodec<PeerMessage> {

    private static final Logger LOG = LoggerFactory.getLogger(PeerMessageCodec.class);

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf buf, List<Object> out) throws Exception {
        if (buf.readableBytes() == 0) {
            out.add(new PeerMessage.KeepAliveMessage());
            return;
        }

        byte type = buf.readByte();

        PeerMessage message;
        switch (type) {
            case 0:
                message = new PeerMessage.ChokeMessage();
                break;
            case 1:
                message = new PeerMessage.UnchokeMessage();
                break;
            case 2:
                message = new PeerMessage.InterestedMessage();
                break;
            case 3:
                message = new PeerMessage.NotInterestedMessage();
                break;
            case 4:
                message = new PeerMessage.HaveMessage();
                break;
            case 5:
                message = new PeerMessage.BitfieldMessage();
                break;
            case 6:
                message = new PeerMessage.RequestMessage();
                break;
            case 7:
                message = new PeerMessage.PieceMessage();
                break;
            case 8:
                message = new PeerMessage.CancelMessage();
                break;
            case 20:
                byte extendedType = buf.readByte();
                switch (extendedType) {
                    case 0:
                        message = new PeerExtendedMessage.HandshakeMessage();
                        break;
                    default:
                        throw new IOException("Unknown extended message type " + extendedType);
                }
            default:
                throw new IOException("Unknown message type " + type);
        }
        message.fromWire(buf);
        out.add(message);

        if (buf.readableBytes() > 0)
            throw new IOException("Badly framed message " + message + "; remaining=" + buf.readableBytes());
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, PeerMessage value, ByteBuf out) throws Exception {
        // LOG.info("encode: " + value);
        value.toWire(out);
    }
}
