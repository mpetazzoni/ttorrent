/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.client.io;

import com.turn.ttorrent.client.peer.PeerMessageListener;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.CombinedChannelDuplexHandler;
import io.netty.handler.logging.LoggingHandler;
import javax.annotation.Nonnull;

/**
 *
 * @author shevek
 */
public class PeerHandshakeHandler extends ChannelInboundHandlerAdapter {

    private static final LoggingHandler messageLogger = new LoggingHandler("peer-message");
    protected static final PeerFrameEncoder frameEncoder = new PeerFrameEncoder();

    @Nonnull
    protected ByteBuf toByteBuf(@Nonnull HandshakeMessage message) {
        ByteBuf buf = Unpooled.buffer(HandshakeMessage.BASE_HANDSHAKE_LENGTH + 64);
        message.toWire(buf);
        return buf;
    }

    protected void addMessageHandlers(@Nonnull ChannelPipeline pipeline, @Nonnull PeerMessageListener listener) {
        pipeline.addLast(new CombinedChannelDuplexHandler(new PeerFrameDecoder(), frameEncoder));
        pipeline.addLast(new PeerMessageCodec());
        pipeline.addLast(messageLogger);
        pipeline.addLast(new PeerMessageHandler(listener));
    }
}
