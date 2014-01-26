/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.client.io;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.logging.LoggingHandler;
import java.util.BitSet;
import javax.annotation.Nonnull;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 *
 * @author shevek
 */
public class PeerMessageTest {

    private static final Log LOG = LogFactory.getLog(PeerMessageTest.class);

    public static class Formatter extends LoggingHandler {

        public String format(String name, ByteBuf buf) {
            return super.formatByteBuf(name, buf);
        }
    }

    @Nonnull
    private <T extends PeerMessage> T testMessage(@Nonnull T in) throws Exception {
        Formatter formatter = new Formatter();

        ByteBuf buf = Unpooled.buffer(1234);

        in.toWire(buf);
        LOG.info(in + " -> " + formatter.format(in.getClass().getSimpleName(), buf));

        T out = (T) in.getClass().newInstance();
        buf.readByte();
        out.fromWire(buf);
        assertEquals(0, buf.readableBytes());

        return out;
    }

    @Test
    public void testChokeMessage() throws Exception {
        testMessage(new PeerMessage.ChokeMessage());
    }

    @Test
    public void testBitfieldMessage() throws Exception {
        testMessage(new PeerMessage.BitfieldMessage(new BitSet()));
        BitSet set = new BitSet();
        set.set(0);
        testMessage(new PeerMessage.BitfieldMessage(set));
        set.set(31);
        testMessage(new PeerMessage.BitfieldMessage(set));
    }

    @Test
    public void testHaveMessage() throws Exception {
        testMessage(new PeerMessage.HaveMessage(0));
        testMessage(new PeerMessage.HaveMessage(0x1234));
        testMessage(new PeerMessage.HaveMessage(0x12345678));
    }
}