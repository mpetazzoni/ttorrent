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
package com.turn.ttorrent.common.protocol.http;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Objects;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimap;
import com.google.common.net.InetAddresses;
import com.turn.ttorrent.common.Torrent;
import com.turn.ttorrent.common.TorrentUtils;
import com.turn.ttorrent.common.protocol.TrackerMessage.AnnounceRequestMessage;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The announce request message for the HTTP tracker protocol.
 *
 * <p>
 * This class represents the announce request message in the HTTP tracker
 * protocol. It doesn't add any specific fields compared to the generic
 * announce request message, but it provides the means to parse such
 * messages and craft them.
 * </p>
 *
 * @author mpetazzoni
 */
public class HTTPAnnounceRequestMessage extends HTTPTrackerMessage
        implements AnnounceRequestMessage {

    private static final Logger LOG = LoggerFactory.getLogger(HTTPAnnounceRequestMessage.class);
    private final byte[] infoHash;
    private final byte[] peerId;
    private final List<InetSocketAddress> peerAddresses;
    private final long uploaded;
    private final long downloaded;
    private final long left;
    private final boolean compact;
    private final boolean noPeerId;
    private final AnnounceEvent event;
    private final int numWant;

    public HTTPAnnounceRequestMessage(
            byte[] infoHash,
            byte[] peerId, List<InetSocketAddress> peerAddresses,
            long uploaded, long downloaded, long left,
            boolean compact, boolean noPeerId, AnnounceEvent event, int numWant) {
        if (peerAddresses.isEmpty())
            throw new IllegalArgumentException("No PeerAddresses specified. Require at least an InetSocketAddress(port).");
        this.infoHash = infoHash;
        this.peerId = peerId;
        this.peerAddresses = peerAddresses;
        this.downloaded = downloaded;
        this.uploaded = uploaded;
        this.left = left;
        this.compact = compact;
        this.noPeerId = noPeerId;
        this.event = event;
        this.numWant = numWant;
    }

    @Override
    public byte[] getInfoHash() {
        return this.infoHash;
    }

    @Override
    public String getHexInfoHash() {
        return TorrentUtils.toHex(this.infoHash);
    }

    @Override
    public byte[] getPeerId() {
        return peerId;
    }

    @Nonnull
    public List<InetSocketAddress> getPeerAddresses() {
        return peerAddresses;
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

    public boolean getCompact() {
        return this.compact;
    }

    public boolean getNoPeerIds() {
        return this.noPeerId;
    }

    @Override
    public AnnounceEvent getEvent() {
        return this.event;
    }

    @Override
    public int getNumWant() {
        return this.numWant;
    }

    @Nonnull
    @VisibleForTesting
    /* pp */ static String toUrlString(@Nonnull byte[] data) throws UnsupportedEncodingException {
        String text = new String(data, Torrent.BYTE_ENCODING);
        return URLEncoder.encode(text, Torrent.BYTE_ENCODING_NAME);
    }

    @Nonnull
    @VisibleForTesting
    /* pp */ static String toUrlString(@Nonnull InetAddress address, int port) throws UnsupportedEncodingException {
        String text = InetAddresses.toUriString(address);
        if (port != -1)
            text = text + ":" + port;
        return URLEncoder.encode(text, Torrent.BYTE_ENCODING_NAME);
    }

    /**
     * Build the announce request URL for the given tracker announce URL.
     *
     * @param trackerAnnounceURL The tracker's announce URL.
     * @return The URL object representing the announce request URL.
     */
    @Nonnull
    public URI toURI(@Nonnull URI trackerAnnounceURL)
            throws UnsupportedEncodingException, URISyntaxException {
        String base = trackerAnnounceURL.toString();
        StringBuilder url = new StringBuilder(base);
        url.append(base.contains("?") ? "&" : "?")
                .append("info_hash=").append(toUrlString(getInfoHash()))
                .append("&peer_id=").append(toUrlString(getPeerId()))
                // .append("&port=").append(getPeerAddress().getPort())
                .append("&uploaded=").append(getUploaded())
                .append("&downloaded=").append(getDownloaded())
                .append("&left=").append(getLeft())
                .append("&compact=").append(getCompact() ? 1 : 0)
                .append("&no_peer_id=").append(getNoPeerIds() ? 1 : 0);

        if (getEvent() != null
                && !AnnounceEvent.NONE.equals(getEvent())) {
            url.append("&event=").append(getEvent().getEventName());
        }

        List<InetSocketAddress> addresses = new ArrayList<InetSocketAddress>();
        Iterables.addAll(addresses, getPeerAddresses());
        boolean port = false;
        for (InetSocketAddress sockaddr : addresses) {
            InetAddress inaddr = sockaddr.getAddress();
            if (!port) {
                url.append("&port=").append(sockaddr.getPort());
                if (inaddr instanceof Inet4Address)
                    url.append("&ip=").append(toUrlString(inaddr, -1));
                else if (inaddr instanceof Inet6Address)
                    url.append("&ipv6=").append(toUrlString(inaddr, -1));
                port = true;
                continue;
            }
            if (inaddr instanceof Inet4Address)
                url.append("&ipv4=").append(toUrlString(inaddr, sockaddr.getPort()));
            else if (inaddr instanceof Inet6Address)
                url.append("&ipv6=").append(toUrlString(inaddr, sockaddr.getPort()));
        }

        if (getNumWant() != AnnounceRequestMessage.DEFAULT_NUM_WANT)
            url.append("&numwant=").append(getNumWant());

        return new URI(url.toString());
    }

    @VisibleForTesting
    @Nonnull
    /* pp */ static InetSocketAddress toInetSocketAddress(@Nonnull String sockstr, int port) {
        if (sockstr.indexOf(':') == -1)
            return new InetSocketAddress(InetAddresses.forString(sockstr), port);
        if (sockstr.startsWith("[")) {
            int idx = sockstr.indexOf("]:");
            if (idx == -1)  // Pure bracket-surrounded IPv6 address.
                return new InetSocketAddress(InetAddresses.forUriString(sockstr), port);
            int port6 = Integer.parseInt(sockstr.substring(idx + 2));
            return new InetSocketAddress(InetAddresses.forUriString(sockstr.substring(0, idx + 1)), port6);
        }
        int idx = sockstr.indexOf(':');
        if (idx != -1) {  // IPv4 plus port
            int port4 = Integer.parseInt(sockstr.substring(idx + 1));
            return new InetSocketAddress(InetAddresses.forUriString(sockstr.substring(0, idx)), port4);
        }
        return new InetSocketAddress(InetAddresses.forUriString(sockstr.substring(0, idx)), port);
    }

    @Nonnull
    public static HTTPAnnounceRequestMessage fromParams(@Nonnull Multimap<String, String> params)
            throws MessageValidationException {

        byte[] infoHash = toBytes(params, "info_hash", ErrorMessage.FailureReason.MISSING_HASH);
        byte[] peerId = toBytes(params, "peer_id", ErrorMessage.FailureReason.MISSING_PEER_ID);

        // Default 'uploaded' and 'downloaded' to 0 if the client does
        // not provide it (although it should, according to the spec).
        long uploaded = toLong(params, "uploaded", 0, null);
        long downloaded = toLong(params, "downloaded", 0, null);
        // Default 'left' to -1 to avoid peers entering the COMPLETED
        // state when they don't provide the 'left' parameter.
        long left = toLong(params, "left", -1, null);

        boolean compact = toBoolean(params, "compact");
        boolean noPeerId = toBoolean(params, "no_peer_id");

        int numWant = toInt(params, "numwant", AnnounceRequestMessage.DEFAULT_NUM_WANT, null);

        AnnounceEvent event = AnnounceEvent.NONE;
        if (params.containsKey("event"))
            event = AnnounceEvent.getByName(toString(params, "event", null));

        List<InetSocketAddress> addresses = new ArrayList<InetSocketAddress>();
        int port = toInt(params, "port", -1, ErrorMessage.FailureReason.MISSING_PORT);

        MAIN:
        {
            String ip = toString(params, "ip", null);
            if (ip != null)
                addresses.add(new InetSocketAddress(InetAddresses.forString(ip), port));
        }

        IP4:
        {
            Collection<String> ips = params.get("ipv4");
            if (ips != null)
                for (String ip : ips)
                    addresses.add(toInetSocketAddress(ip, port));
        }

        IP6:
        {
            Collection<String> ips = params.get("ipv6");
            if (ips != null)
                for (String ip : ips)
                    addresses.add(toInetSocketAddress(ip, port));
        }

        DEFAULT:
        {
            if (addresses.isEmpty())
                addresses.add(new InetSocketAddress(port));
        }

        return new HTTPAnnounceRequestMessage(infoHash,
                peerId, addresses,
                uploaded, downloaded, left, compact, noPeerId,
                event, numWant);
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("infoHash", getHexInfoHash())
                .add("peerId", TorrentUtils.toHex(peerId))
                .add("peerAddresses", peerAddresses)
                .add("uploaded", uploaded)
                .add("downloaded", downloaded)
                .add("left", left)
                .add("compact", compact)
                .add("noPeerId", noPeerId)
                .add("event", event)
                .add("numWant", numWant)
                .toString();
    }
}