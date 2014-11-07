/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.client.io;

import io.netty.handler.traffic.ChannelTrafficShapingHandler;
import java.util.concurrent.TimeUnit;

/**
 *
 * @author shevek
 */
public class PeerMessageTrafficShapingHandler extends ChannelTrafficShapingHandler {

    public PeerMessageTrafficShapingHandler() {
        super(TimeUnit.MINUTES.toMillis(1));
    }

    @Override
    protected long calculateSize(Object msg) {
        if (msg instanceof PeerMessage.PieceMessage)
            return ((PeerMessage.PieceMessage) msg).getLength();
        return super.calculateSize(msg);
    }
}
