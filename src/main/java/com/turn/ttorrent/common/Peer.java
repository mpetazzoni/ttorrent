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
import java.util.Arrays;
import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;

/**
 * A basic BitTorrent peer.
 *
 * <p>
 * This class is meant to be a common base for the tracker and client, which
 * would presumably subclass it to extend its functionality and fields.
 * </p>
 *
 * @author mpetazzoni
 */
public class Peer {

    private final InetSocketAddress address;
    // On UDP, this is nullable. On HTTP, it isn't.
    private final byte[] peerId;

    /**
     * Instantiate a new peer.
     *
     * @param address The peer's address, with port.
     */
    public Peer(@Nonnull InetSocketAddress address, byte[] peerId) {
        this.address = address;
        this.peerId = peerId;
    }

    /**
     * Returns the raw peer ID.
     */
    public byte[] getPeerId() {
        return this.peerId;
    }

    public boolean hasPeerId() {
        return peerId != null;
    }

    /**
     * Get the hexadecimal-encoded string representation of this peer's ID.
     */
    @Nonnull
    public String getHexPeerId() {
        /*
         byte[] peerId = getPeerId();
         if (peerId == null)
         return null;
         */
        return Torrent.byteArrayToHexString(getPeerId());
    }

    /**
     * Get the shortened hexadecimal-encoded peer ID.
     */
    @Nonnull
    public String getShortHexPeerId() {
        String hexPeerId = getHexPeerId();
        return hexPeerId.substring(hexPeerId.length() - 6);
    }

    @Nonnull
    public InetSocketAddress getAddress() {
        return address;
    }

    @Nonnull
    public byte[] getIpAddress() {
        return getAddress().getAddress().getAddress();
    }

    @Nonnull
    public String getIp() {
        InetAddress inetAddress = getAddress().getAddress();
        if (inetAddress == null)
            throw new NullPointerException("InetSocketAddress contained no InetAddress.");
        return inetAddress.getHostAddress();
    }

    @Nonnegative
    public int getPort() {
        return getAddress().getPort();
    }

    /**
     * Returns this peer's host identifier ("host:port").
     */
    @Nonnull
    public String getHostIdentifier() {
        return getIp() + ":" + getPort();
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
        StringBuilder s = new StringBuilder("peer://")
                .append(getHostIdentifier())
                .append("/");
        if (hasPeerId())
            s.append(getShortHexPeerId());
        else
            s.append("?");
        return s.toString();
    }
}