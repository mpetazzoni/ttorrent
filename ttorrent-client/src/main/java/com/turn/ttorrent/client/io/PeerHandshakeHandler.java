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
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.ReferenceCountUtil;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author shevek
 * @see <a href="http://wiki.theory.org/BitTorrentSpecification#Handshake">BitTorrent handshake specification</a>
 * @see PeerServerHandshakeHandler
 * @see PeerClientHandshakeHandler
 */
public abstract class PeerHandshakeHandler extends LengthFieldBasedFrameDecoder {

    private static final Logger LOG = LoggerFactory.getLogger(PeerHandshakeHandler.class);

    // protected static final PeerFrameEncoder frameEncoder = new PeerFrameEncoder();
    public PeerHandshakeHandler() {
        super(1024, 0, 1, PeerHandshakeMessage.BASE_HANDSHAKE_LENGTH - 1, 0);
    }

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

    private void addMessageHandlers(@Nonnull ChannelPipeline pipeline, @Nonnull PeerMessageListener listener) {
        // TODO: Merge LengthFieldPrepender into PeerMessageCodec and use only a PeerFrameDecoder here.
        pipeline.addLast(new PeerFrameDecoder());
        // pipeline.addLast(frameEncoder);
        // pipeline.addLast(getFrameLogger());
        pipeline.addLast(new PeerMessageCodec(listener));
        // pipeline.addLast(getMessageLogger());
        // pipeline.addLast(new PeerMessageTrafficShapingHandler());
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
    protected Object decode(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
        Object message = super.decode(ctx, in);
        if (message instanceof ByteBuf) {
            PeerHandshakeMessage request = new PeerHandshakeMessage();
            request.fromWire((ByteBuf) message);
            process(ctx, request);
            ReferenceCountUtil.release(message);
            return null;
        } else {
            // Including null.
            return message;
        }
    }
}