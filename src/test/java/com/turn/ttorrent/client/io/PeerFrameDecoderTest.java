/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.client.io;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.junit.Test;

/**
 *
 * @author shevek
 */
public class PeerFrameDecoderTest {

    private static final Log LOG = LogFactory.getLog(PeerFrameDecoderTest.class);

    @Test
    public void testDecoder() throws Exception {
        ByteBuf in = Unpooled.buffer(27);
        in.writeInt(6);
        in.writeBytes(new byte[]{1, 2, 3, 4, 5, 6});

        PeerFrameDecoder decoder = new PeerFrameDecoder() {
            @Override
            protected ByteBuf extractFrame(ChannelHandlerContext ctx, ByteBuf buffer, int index, int length) {
                ByteBuf frame = Unpooled.buffer(length);
                frame.writeBytes(buffer, index, length);
                return frame;
            }
        };
        ByteBuf out = decoder._decode(null, in);

        PeerMessageTest.Formatter formatter = new PeerMessageTest.Formatter();
        LOG.info(formatter.format("decoded", out));
    }
}