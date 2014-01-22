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
import com.turn.ttorrent.client.TorrentHandler;
import com.turn.ttorrent.client.peer.PeerHandler;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.logging.LoggingHandler;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author shevek
 */
public class PeerServerHandshakeHandler extends PeerHandshakeHandler {

    private static final Logger LOG = LoggerFactory.getLogger(PeerServerHandshakeHandler.class);
    private static final LoggingHandler wireLogger = new LoggingHandler("server-wire");
    private final Client client;

    public PeerServerHandshakeHandler(@Nonnull Client client) {
        this.client = client;
    }

    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        ctx.pipeline().addFirst(wireLogger);
        super.channelRegistered(ctx);
    }

    protected void process(ChannelHandlerContext ctx, HandshakeMessage message) {
        LOG.info("Processing " + message);
        // We are a server.
        TorrentHandler torrent = client.getTorrent(message.getInfoHash());
        if (torrent == null) {
            LOG.warn("Unknown torrent " + message);
            ctx.close();
            return;
        }
        LOG.info("Found torrent " + torrent);
        HandshakeMessage response = new HandshakeMessage(torrent.getInfoHash(), client.getPeerId());
        ctx.writeAndFlush(toByteBuf(response));

        Channel channel = ctx.channel();
        PeerHandler peer = torrent.getSwarmHandler().getOrCreatePeer(channel.remoteAddress(), message.getPeerId());

        addMessageHandlers(ctx.pipeline(), peer);
        ctx.pipeline().remove(this);

        PeerConnectionListener listener = torrent.getSwarmHandler();
        listener.handleNewPeerConnection(channel, peer);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        ByteBuf in = (ByteBuf) msg;
        LOG.info("Read " + in + " with " + in.readableBytes() + " bytes");
        if (in.readableBytes() < HandshakeMessage.BASE_HANDSHAKE_LENGTH)
            return;

        int length = in.getUnsignedByte(0);
        LOG.info("Length byte is " + length);
        if (in.readableBytes() < HandshakeMessage.BASE_HANDSHAKE_LENGTH + length)
            return;

        LOG.info("Parsing HandshakeMessage.");
        HandshakeMessage request = new HandshakeMessage();
        request.fromWire(in);

        process(ctx, request);
    }
}