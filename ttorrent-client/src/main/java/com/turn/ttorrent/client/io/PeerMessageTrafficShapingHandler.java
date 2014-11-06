/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.client.io;

import io.netty.handler.traffic.ChannelTrafficShapingHandler;

/**
 *
 * @author shevek
 */
public class PeerMessageTrafficShapingHandler extends ChannelTrafficShapingHandler {

    public PeerMessageTrafficShapingHandler(long checkInterval) {
        super(checkInterval);
    }

    @Override
    protected long calculateSize(Object msg) {
        if (msg instanceof PeerMessage.PieceMessage)
            return ((PeerMessage.PieceMessage) msg).getLength();
        return super.calculateSize(msg);
    }
}
