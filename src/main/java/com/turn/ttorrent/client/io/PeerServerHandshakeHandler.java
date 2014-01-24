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
    private static final LoggingHandler messageLogger = new LoggingHandler("server-message");
    private final Client client;

    public PeerServerHandshakeHandler(@Nonnull Client client) {
        this.client = client;
    }

    @Override
    public LoggingHandler getWireLogger() {
        return wireLogger;
    }

    @Override
    public LoggingHandler getMessageLogger() {
        return messageLogger;
    }

    @Override
    protected void process(ChannelHandlerContext ctx, HandshakeMessage message) {
        LOG.info("Processing " + message);
        // We are a server.
        TorrentHandler torrent = client.getTorrent(message.getInfoHash());
        if (torrent == null) {
            LOG.warn("Unknown torrent " + message);
            ctx.close();
            return;
        }
        PeerConnectionListener listener = torrent.getSwarmHandler();
        LOG.info("Found torrent " + torrent);

        HandshakeMessage response = new HandshakeMessage(torrent.getInfoHash(), client.getPeerId());
        ctx.writeAndFlush(toByteBuf(response));

        addPeer(ctx, message, listener);
    }
}