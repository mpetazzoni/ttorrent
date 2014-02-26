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
import com.turn.ttorrent.common.TorrentUtils;
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
public class PeerClientHandshakeHandler extends PeerHandshakeHandler {

    private static final Logger logger = LoggerFactory.getLogger(PeerClientHandshakeHandler.class);
    private static final LoggingHandler wireLogger = new LoggingHandler("client-wire");
    private static final LoggingHandler frameLogger = new LoggingHandler("client-frame");
    private static final LoggingHandler messageLogger = new LoggingHandler("client-message");
    @Nonnull
    private final byte[] infoHash;
    @Nonnull
    private final byte[] peerId;
    @Nonnull
    private final PeerConnectionListener listener;

    @SuppressWarnings("EI_EXPOSE_REP2")
    public PeerClientHandshakeHandler(
            @Nonnull PeerConnectionListener listener,
            @Nonnull byte[] infoHash,
            @Nonnull byte[] peerId) {
        this.listener = listener;
        this.infoHash = infoHash;
        this.peerId = peerId;
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
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        PeerHandshakeMessage response = new PeerHandshakeMessage(infoHash, peerId);
        ctx.writeAndFlush(toByteBuf(response));
        super.channelActive(ctx);
    }

    @Override
    protected void process(ChannelHandlerContext ctx, PeerHandshakeMessage message) {
        // We were the connecting client.
        if (!Arrays.equals(infoHash, message.getInfoHash())) {
            logger.warn("InfoHash mismatch: requested " + TorrentUtils.toHex(infoHash) + " but received " + message);
            ctx.close();
            return;
        }

        addPeer(ctx, message, listener);
    }
}