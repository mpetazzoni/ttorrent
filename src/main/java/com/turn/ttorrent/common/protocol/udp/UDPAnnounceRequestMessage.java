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

import com.turn.ttorrent.common.Peer;
import com.turn.ttorrent.common.Torrent;
import com.turn.ttorrent.common.protocol.TrackerMessage;

import io.netty.buffer.ByteBuf;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

/**
 * The announce request message for the UDP tracker protocol.
 *
 * @author mpetazzoni
 */
public class UDPAnnounceRequestMessage
        extends UDPTrackerMessage.UDPTrackerRequestMessage
        implements TrackerMessage.AnnounceRequestMessage {

    private static final int UDP_ANNOUNCE_REQUEST_MESSAGE_SIZE = 98;
    private byte[] infoHash;
    private Peer peer;
    private long downloaded;
    private long uploaded;
    private long left;
    private RequestEvent event;
    private int numWant;
    private int key;

    public UDPAnnounceRequestMessage() {
        super(Type.ANNOUNCE_REQUEST);

        /*
         if (infoHash.length != 20 || peerId.length != 20) {
         throw new IllegalArgumentException();
         }

         if (!(ip instanceof Inet4Address)) {
         throw new IllegalArgumentException("Only IPv4 addresses are "
         + "supported by the UDP tracer protocol!");
         }
         */
    }

    public UDPAnnounceRequestMessage(
            long connectionId, int transactionId,
            byte[] infoHash, byte[] peerId,
            long downloaded, long uploaded, long left,
            RequestEvent event, InetAddress ip, int numWant, int key, int port) {
        this();
        setConnectionId(connectionId);
        setTransactionId(transactionId);
        this.infoHash = infoHash;
        this.peer = new Peer(new InetSocketAddress(ip, port), peerId);
        this.downloaded = downloaded;
        this.uploaded = uploaded;
        this.left = left;
        this.event = event;
        this.numWant = numWant;
        this.key = key;
    }

    @Override
    public byte[] getInfoHash() {
        return this.infoHash;
    }

    @Override
    public String getHexInfoHash() {
        return Torrent.byteArrayToHexString(this.infoHash);
    }

    @Override
    public Peer getPeer() {
        return peer;
    }

    @Override
    public long getUploaded() {
        return this.uploaded;
    }

    @Override
    public long getDownloaded() {
        return this.downloaded;
    }

    @Override
    public long getLeft() {
        return this.left;
    }

    @Override
    public boolean getCompact() {
        return true;
    }

    @Override
    public boolean getNoPeerIds() {
        return true;
    }

    @Override
    public RequestEvent getEvent() {
        return this.event;
    }

    @Override
    public int getNumWant() {
        return this.numWant;
    }

    public int getKey() {
        return this.key;
    }

    @Override
    public void fromWire(ByteBuf in) throws MessageValidationException {
        _fromWire(in, UDP_ANNOUNCE_REQUEST_MESSAGE_SIZE);

        infoHash = new byte[20];
        in.readBytes(infoHash);
        byte[] peerId = new byte[20];
        in.readBytes(peerId);

        downloaded = in.readLong();
        uploaded = in.readLong();
        left = in.readLong();

        event = RequestEvent.getById(in.readInt());
        if (event == null)
            throw new MessageValidationException("Invalid event type in announce request!");

        InetAddress address;
        try {
            byte[] ipBytes = new byte[4];
            in.readBytes(ipBytes);
            address = InetAddress.getByAddress(ipBytes);
        } catch (UnknownHostException uhe) {
            throw new MessageValidationException(
                    "Invalid IP address in announce request!");
        }

        key = in.readInt();
        numWant = in.readInt();
        int port = in.readShort() & 0xFFFF;
        peer = new Peer(new InetSocketAddress(address, port), peerId);
    }

    @Override
    public void toWire(ByteBuf out) {
        _toWire(out);
        out.writeBytes(infoHash);
        out.writeBytes(peer.getPeerId());
        out.writeLong(downloaded);
        out.writeLong(uploaded);
        out.writeLong(left);
        out.writeInt(event.getId());
        out.writeBytes(peer.getIpAddress());
        out.writeInt(key);
        out.writeInt(numWant);
        out.writeShort((short) peer.getPort());
    }
}