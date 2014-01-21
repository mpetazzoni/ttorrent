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

import com.turn.ttorrent.client.Client;
import com.turn.ttorrent.client.peer.PeerConnectionListener;
import com.turn.ttorrent.client.peer.SharingPeer;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.SocketChannel;
import java.util.Arrays;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author shevek
 */
public class PeerClientHandshakeHandler extends ChannelInboundHandlerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(PeerClientHandshakeHandler.class);
    @Nonnull
    private final Client client;
    @Nonnull
    private final SharingPeer peer;
    @Nonnull
    private final PeerConnectionListener listener;

    public PeerClientHandshakeHandler(@Nonnull Client client,
            @Nonnull SharingPeer peer,
            @Nonnull PeerConnectionListener listener) {
        this.client = client;
        this.peer = peer;
        this.listener = listener;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        // Client only.
        ctx.writeAndFlush(new HandshakeMessage(peer.getInfoHash(), client.getPeerId()));
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