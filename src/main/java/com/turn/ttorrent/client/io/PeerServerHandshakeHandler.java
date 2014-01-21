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
import com.turn.ttorrent.client.SharedTorrent;
import com.turn.ttorrent.client.peer.SharingPeer;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.SocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author shevek
 */
public class PeerServerHandshakeHandler extends ChannelInboundHandlerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(PeerServerHandshakeHandler.class);
    private final Client client;
    private final PeerChannelInitializer initializer;

    public PeerServerHandshakeHandler(Client client, PeerChannelInitializer initializer) {
        this.client = client;
        this.initializer = initializer;
    }

    protected void process(ChannelHandlerContext ctx, HandshakeMessage message) {
        // We are a server.
        SharedTorrent torrent = client.getTorrent(message.getInfoHash());
        if (torrent == null) {
            logger.warn("Unknown torrent " + message);
            ctx.close();
            return;
        }
        HandshakeMessage response = new HandshakeMessage(torrent.getInfoHash(), client.getPeerId());
        ctx.writeAndFlush(response);

        SocketChannel channel = (SocketChannel) ctx.channel();
        SharingPeer sharingPeer = torrent.getPeerHandler().getOrCreatePeer(channel.remoteAddress(), message.getPeerId());

        ctx.pipeline().remove(this);
        initializer.initChannelPipeline(ctx.pipeline(), sharingPeer);

        PeerConnectionListener listener = torrent.getPeerHandler();
        listener.handleNewPeerConnection(channel, sharingPeer);
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

        process(ctx, request);
    }
}