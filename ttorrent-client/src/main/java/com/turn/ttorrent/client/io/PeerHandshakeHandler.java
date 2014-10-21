/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.client.io;

import com.turn.ttorrent.client.peer.PeerConnectionListener;
import com.turn.ttorrent.client.peer.PeerHandler;
import com.turn.ttorrent.client.peer.PeerMessageListener;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.CombinedChannelDuplexHandler;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.ReferenceCountUtil;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author shevek
 */
public abstract class PeerHandshakeHandler extends ChannelInboundHandlerAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(PeerHandshakeHandler.class);
    protected static final PeerFrameEncoder frameEncoder = new PeerFrameEncoder();

    @Nonnull
    protected abstract LoggingHandler getWireLogger();

    @Nonnull
    protected abstract LoggingHandler getFrameLogger();

    @Nonnull
    protected abstract LoggingHandler getMessageLogger();

    @Nonnull
    protected ByteBuf toByteBuf(@Nonnull ChannelHandlerContext ctx, @Nonnull PeerHandshakeMessage message) {
        ByteBuf buf = ctx.alloc().buffer(PeerHandshakeMessage.BASE_HANDSHAKE_LENGTH + 64);
        message.toWire(buf);
        return buf;
    }

    protected void addMessageHandlers(@Nonnull ChannelPipeline pipeline, @Nonnull PeerMessageListener listener) {
        // TODO: Merge LengthFieldPrepender into PeerMessageCodec and use only a PeerFrameDecoder here.
        pipeline.addLast(new CombinedChannelDuplexHandler(new PeerFrameDecoder(), frameEncoder));
        // pipeline.addLast(getFrameLogger());
        pipeline.addLast(new PeerMessageCodec(listener));
        // pipeline.addLast(getMessageLogger());
        pipeline.addLast(new PeerMessageHandler(listener));
    }

    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        // ctx.pipeline().addFirst(getWireLogger());
        super.channelRegistered(ctx);
    }

    protected abstract void process(@Nonnull ChannelHandlerContext ctx, @Nonnull PeerHandshakeMessage message);

    protected void addPeer(@Nonnull ChannelHandlerContext ctx, @Nonnull PeerHandshakeMessage message,
            @Nonnull PeerConnectionListener listener) {
        Channel channel = ctx.channel();
        PeerHandler peer = listener.handlePeerConnectionCreated(channel, message.getPeerId(), message.getReserved());
        if (peer == null) {
            ctx.close().addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
            return;
        }

        addMessageHandlers(ctx.pipeline(), peer);
        ctx.pipeline().remove(this);

        listener.handlePeerConnectionReady(peer);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        try {
            ByteBuf in = (ByteBuf) msg;
            if (in.readableBytes() < PeerHandshakeMessage.BASE_HANDSHAKE_LENGTH)
                return;

            int length = in.getUnsignedByte(0);
            if (in.readableBytes() < PeerHandshakeMessage.BASE_HANDSHAKE_LENGTH + length)
                return;

            PeerHandshakeMessage request = new PeerHandshakeMessage();
            request.fromWire(in);

            process(ctx, request);
        } finally {
            ReferenceCountUtil.release(msg);
        }
    }
}