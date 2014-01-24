/**
 * Copyright (C) 2011-2012 Turn, Inc.
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
package com.turn.ttorrent.common;

import com.turn.ttorrent.bcodec.BEValue;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.CheckForNull;
import javax.annotation.CheckForSigned;
import javax.annotation.Nonnull;

/**
 * A basic BitTorrent peer.
 *
 * <p>
 * We can't use this class in as many places as we'd like because it does
 * not have strong nonnull or type guarantees.
 * 
 * Peer-to-peer traffic needs a SocketAddress and a PeerId, whereas the tracker
 * needs an InetSocketAddress but PeerId is optional.
 * </p>
 *
 * @author mpetazzoni
 */
public class Peer {

    public static boolean isValidIpAddress(@CheckForNull SocketAddress sa) {
        if (!(sa instanceof InetSocketAddress))
            return false;
        InetSocketAddress isa = (InetSocketAddress) sa;
        return isValidIpAddress(isa.getAddress());
    }

    public static boolean isValidIpAddress(@CheckForNull InetAddress ia) {
        if (ia == null)
            return false;
        byte[] ba = ia.getAddress();
        if (ba == null)
            return false;
        for (byte b : ba)
            if (b != 0)
                return true;
        return false;
    }
    private final SocketAddress address;
    // On UDP and HTTP-compact this is nullable.
    @CheckForNull
    private final byte[] peerId;

    /**
     * Instantiate a new peer.
     *
     * @param address The peer's address, with port.
     */
    public Peer(@Nonnull SocketAddress address, @CheckForNull byte[] peerId) {
        if (!isValidIpAddress(address))
            throw new IllegalArgumentException("Invalid SocketAddress: " + address);
        if (peerId != null && peerId.length != 20)
            throw new IllegalArgumentException("PeerId length should be 20, not " + peerId.length);
        this.address = address;
        this.peerId = peerId;
    }

    /**
     * Returns the raw peer ID.
     */
    @CheckForNull
    public byte[] getPeerId() {
        return this.peerId;
    }

    public boolean hasPeerId() {
        return peerId != null;
    }

    /**
     * Get the hexadecimal-encoded string representation of this peer's ID.
     */
    @CheckForNull
    public String getHexPeerId() {
        byte[] peerId = getPeerId();
        if (peerId == null)
            return null;
        return Torrent.byteArrayToHexString(peerId);
    }

    /**
     * Get the shortened hexadecimal-encoded peer ID.
     */
    @CheckForNull
    private String getShortHexPeerId() {
        String hexPeerId = getHexPeerId();
        if (hexPeerId == null)
            return null;
        return hexPeerId.substring(hexPeerId.length() - 6);
    }

    @Nonnull
    public SocketAddress getAddress() {
        return address;
    }

    @CheckForNull
    private InetAddress getInetAddress() {
        SocketAddress sa = getAddress();
        if (!(sa instanceof InetSocketAddress))
            return null;
        InetSocketAddress isa = (InetSocketAddress) sa;
        return isa.getAddress();
    }

    @CheckForNull
    public byte[] getIpBytes() {
        InetAddress ia = getInetAddress();
        if (ia == null)
            return null;
        return ia.getAddress();
    }

    @CheckForNull
    public String getIpString() {
        InetAddress ia = getInetAddress();
        if (ia == null)
            return null;
        return ia.getHostAddress();
    }

    @CheckForSigned
    public int getPort() {
        SocketAddress sa = getAddress();
        if (!(sa instanceof InetSocketAddress))
            return -1;
        InetSocketAddress isa = (InetSocketAddress) sa;
        return isa.getPort();
    }

    /**
     * Returns this peer's host identifier ("host:port").
     */
    @Nonnull
    public String getHostIdentifier() {
        return getIpString() + ":" + getPort();
    }

    @Override
    public int hashCode() {
        return getAddress().hashCode() ^ Arrays.hashCode(getPeerId());
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (null == obj)
            return false;
        if (!getClass().equals(obj.getClass()))
            return false;
        Peer other = (Peer) obj;
        return this.address.equals(other.address)
                && Arrays.equals(peerId, other.peerId);
    }

    public boolean matches(@Nonnull Peer other) {
        if (!this.address.equals(other.address))
            return false;
        if (!hasPeerId())
            return true;
        return Arrays.equals(peerId, other.peerId);
    }

    /**
     * Returns a BEValue representing this peer for inclusion in an
     * announce reply from the tracker.
     *
     * The returned BEValue is a dictionary containing the peer ID (in its
     * original byte-encoded form), the peer's IP and the peer's port.
     */
    // TODO: Use this for noncompact tracking.
    public BEValue toBEValue() throws UnsupportedEncodingException {
        Map<String, BEValue> out = new HashMap<String, BEValue>();
        byte[] peerId = getPeerId();
        if (peerId != null)
            out.put("peer id", new BEValue(peerId));
        String ip = getIpString();
        if (ip != null)
            out.put("ip", new BEValue(ip, Torrent.BYTE_ENCODING));
        int port = getPort();
        if (port != -1)
            out.put("port", new BEValue(port));
        return new BEValue(out);
    }

    /**
     * Returns a human-readable representation of this peer.
     */
    @Override
    public String toString() {
        StringBuilder s = new StringBuilder("peer://")
                .append(getAddress())
                .append("/");
        String hexPeerId = getShortHexPeerId();
        if (hexPeerId != null)
            s.append(hexPeerId);
        else
            s.append("?");
        return s.toString();
    }
}