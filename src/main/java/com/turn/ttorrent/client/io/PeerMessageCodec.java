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

import com.turn.ttorrent.common.protocol.PeerMessage.Type;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageCodec;
import java.io.IOException;
import java.util.List;

/**
 *
 * @author shevek
 */
// Not shareable
public class PeerMessageCodec extends ByteToMessageCodec<PeerMessage> {

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf buf, List<Object> out) throws Exception {
        if (buf.readableBytes() == 0) {
            out.add(new PeerMessage.KeepAliveMessage());
            return;
        }

        byte typeCode = buf.readByte();
        Type type = Type.get(typeCode);
        if (type == null)
            throw new PeerMessage.MessageValidationException(null);

        PeerMessage message;
        switch (type) {
            case CHOKE:
                message = new PeerMessage.ChokeMessage();
                break;
            case UNCHOKE:
                message = new PeerMessage.UnchokeMessage();
                break;
            case INTERESTED:
                message = new PeerMessage.InterestedMessage();
                break;
            case NOT_INTERESTED:
                message = new PeerMessage.NotInterestedMessage();
                break;
            case HAVE:
                message = new PeerMessage.HaveMessage();
                break;
            case BITFIELD:
                message = new PeerMessage.BitfieldMessage();
                break;
            case REQUEST:
                message = new PeerMessage.RequestMessage();
                break;
            case PIECE:
                message = new PeerMessage.PieceMessage();
                break;
            case CANCEL:
                message = new PeerMessage.CancelMessage();
                break;
            default:
                throw new IllegalStateException("Message type should have "
                        + "been properly defined by now.");
        }
        message.fromWire(buf);
        out.add(message);

        if (buf.readableBytes() > 0)
            throw new IOException("Badly framed message " + message + "; remaining=" + buf.readableBytes());
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, PeerMessage value, ByteBuf out) throws Exception {
        value.toWire(out);
    }
}
