/**
 * Copyright (C) 2012 Turn, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.turn.ttorrent.common.protocol.udp;

import com.google.common.collect.Collections2;
import com.turn.ttorrent.common.Peer;
import com.turn.ttorrent.common.protocol.TrackerMessage;

import io.netty.buffer.ByteBuf;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The announce response message for the UDP tracker protocol.
 *
 * @author mpetazzoni
 */
public class UDPAnnounceResponseMessage
        extends UDPTrackerMessage.UDPTrackerResponseMessage
        implements TrackerMessage.AnnounceResponseMessage {

    private static final int UDP_ANNOUNCE_RESPONSE_MIN_MESSAGE_SIZE = 20;
    private int interval;
    private int complete;
    private int incomplete;
    private final List<Peer> peers = new ArrayList<Peer>();

    private UDPAnnounceResponseMessage() {
        super(Type.ANNOUNCE_REQUEST);
    }

    @Override
    public int getInterval() {
        return this.interval;
    }

    @Override
    public int getComplete() {
        return this.complete;
    }

    @Override
    public int getIncomplete() {
        return this.incomplete;
    }

    @Override
    public Collection<Peer> getPeers() {
        return this.peers;
    }

    @Override
    public Collection<? extends SocketAddress> getPeerAddresses() {
        return Collections2.transform(getPeers(), PEERADDRESS);
    }

    @Override
    public void fromWire(ByteBuf in) throws MessageValidationException {
        if (in.readableBytes() < UDP_ANNOUNCE_RESPONSE_MIN_MESSAGE_SIZE
                || (in.readableBytes() - UDP_ANNOUNCE_RESPONSE_MIN_MESSAGE_SIZE) % 6 != 0) {
            throw new MessageValidationException("Invalid announce response message size " + in.readableBytes());
        }
        _fromWire(in, -1);

        interval = in.readInt();
        incomplete = in.readInt();
        complete = in.readInt();

        peers.clear();
        while (in.readableBytes() > 0) {
            try {
                byte[] ipBytes = new byte[4];
                in.readBytes(ipBytes);
                InetAddress ip = InetAddress.getByAddress(ipBytes);
                int port = in.readShort() & 0xFFFF;
                peers.add(new Peer(new InetSocketAddress(ip, port), null));
            } catch (UnknownHostException uhe) {
                throw new MessageValidationException(
                        "Invalid IP address in announce request!");
            }
        }
    }

    @Override
    public void toWire(ByteBuf out) {
        _toWire(out);
        out.writeInt(interval);

        /**
         * Leechers (incomplete) are first, before seeders (complete) in the packet.
         */
        out.writeInt(incomplete);
        out.writeInt(complete);

        for (Peer peer : peers) {
            byte[] ip = peer.getIpBytes();
            if (ip == null || ip.length != 4)
                continue;
            out.writeBytes(ip);
            out.writeShort((short) peer.getPort());
        }
    }
}