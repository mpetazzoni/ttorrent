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

import com.turn.ttorrent.client.TorrentRegistry;
import com.turn.ttorrent.client.peer.PeerConnectionListener;
import com.turn.ttorrent.client.TorrentHandler;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.logging.LoggingHandler;
import java.util.Arrays;
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
    private static final LoggingHandler frameLogger = new LoggingHandler("server-frame");
    private static final LoggingHandler messageLogger = new LoggingHandler("server-message");
    private final TorrentRegistry torrentProvider;

    public PeerServerHandshakeHandler(@Nonnull TorrentRegistry torrentProvider) {
        this.torrentProvider = torrentProvider;
    }

    @Override
    public LoggingHandler getWireLogger() {
        return wireLogger;
    }

    @Override
    protected LoggingHandler getFrameLogger() {
        return frameLogger;
    }

    @Override
    public LoggingHandler getMessageLogger() {
        return messageLogger;
    }

    @Override
    protected void process(ChannelHandlerContext ctx, PeerHandshakeMessage message) {
        if (LOG.isTraceEnabled())
            LOG.trace("Processing {}", message);
        if (Arrays.equals(message.getPeerId(), torrentProvider.getLocalPeerId())) {
            LOG.warn("Connected to self. Closing.");
            ctx.close().addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
            return;
        }

        // We are a server.
        TorrentHandler torrent = torrentProvider.getTorrent(message.getInfoHash());
        if (torrent == null) {
            LOG.warn("Unknown torrent {}", message);
            ctx.close().addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
            return;
        }
        PeerConnectionListener listener = torrent.getSwarmHandler();
        if (LOG.isTraceEnabled())
            LOG.trace("Found torrent {}", torrent);

        PeerHandshakeMessage response = new PeerHandshakeMessage(torrent.getInfoHash(), torrentProvider.getLocalPeerId());
        ctx.writeAndFlush(toByteBuf(ctx, response), ctx.voidPromise());

        addPeer(ctx, message, listener);
    }
}