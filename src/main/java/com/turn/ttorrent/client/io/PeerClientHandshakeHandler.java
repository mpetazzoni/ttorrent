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

import com.turn.ttorrent.client.peer.PeerConnectionListener;
import com.turn.ttorrent.client.peer.PeerHandler;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.logging.LoggingHandler;
import java.util.Arrays;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author shevek
 */
public class PeerClientHandshakeHandler extends PeerHandshakeHandler {

    private static final Logger logger = LoggerFactory.getLogger(PeerClientHandshakeHandler.class);
    private static final LoggingHandler wireLogger = new LoggingHandler("client-wire");
    @Nonnull
    private final byte[] peerId;
    @Nonnull
    private final PeerHandler peer;
    @Nonnull
    private final PeerConnectionListener listener;

    public PeerClientHandshakeHandler(@Nonnull byte[] peerId,
            @Nonnull PeerHandler peer,
            @Nonnull PeerConnectionListener listener) {
        this.peerId = peerId;
        this.peer = peer;
        this.listener = listener;
    }

    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        ctx.pipeline().addFirst(wireLogger);
        addMessageHandlers(ctx.pipeline(), peer);
        super.channelRegistered(ctx);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        // Client only.
        HandshakeMessage response = new HandshakeMessage(peer.getInfoHash(), peerId);
        ctx.writeAndFlush(toByteBuf(response));
        super.channelActive(ctx);
    }

    protected void process(ChannelHandlerContext ctx, HandshakeMessage message) {
        // We were the connecting client.
        if (!Arrays.equals(peer.getInfoHash(), message.getInfoHash())) {
            logger.warn("InfoHash mismatch: requested " + peer + " but received " + message);
            ctx.close();
            return;
        }

        ctx.pipeline().remove(this);

        listener.handleNewPeerConnection((SocketChannel) ctx.channel(), peer);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        ByteBuf in = (ByteBuf) msg;
        if (in.readableBytes() < HandshakeMessage.BASE_HANDSHAKE_LENGTH)
            return;

        int length = in.getUnsignedByte(0);
        if (in.readableBytes() < HandshakeMessage.BASE_HANDSHAKE_LENGTH + length)
            return;

        HandshakeMessage request = new HandshakeMessage();
        request.fromWire(in);
    }
}