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

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Arrays;
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

    public static final int PEER_ID_LENGTH = 20;

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
        return isValidIpAddress(ba);
    }

    public static boolean isValidIpAddress(@CheckForNull byte[] ba) {
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
    @SuppressWarnings("EI_EXPOSE_REP2")
    public Peer(@Nonnull SocketAddress address, @CheckForNull byte[] peerId) {
        if (!isValidIpAddress(address))
            throw new IllegalArgumentException("Invalid SocketAddress: " + address);
        if (peerId != null && peerId.length != PEER_ID_LENGTH)
            throw new IllegalArgumentException("PeerId length should be " + PEER_ID_LENGTH + ", not " + peerId.length);
        this.address = address;
        this.peerId = peerId;
    }

    /**
     * Returns the raw peer ID.
     */
    @CheckForNull
    @SuppressWarnings("EI_EXPOSE_REP")
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
        return TorrentUtils.toHex(peerId);
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
     * Returns a human-readable representation of this peer.
     */
    @Override
    public String toString() {
        // TODO: Use InetAddresses.toUriString() when possible.
        // Right now this generates peer:///1.2.3.4/ which has three slashes in it.
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