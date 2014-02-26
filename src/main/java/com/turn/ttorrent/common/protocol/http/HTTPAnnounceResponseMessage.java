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

import com.google.common.base.Objects;
import com.google.common.collect.Collections2;
import com.google.common.net.InetAddresses;
import com.turn.ttorrent.bcodec.BEUtils;
import com.turn.ttorrent.bcodec.BEValue;
import com.turn.ttorrent.bcodec.InvalidBEncodingException;
import com.turn.ttorrent.common.Peer;
import com.turn.ttorrent.common.Torrent;
import com.turn.ttorrent.common.protocol.TrackerMessage.AnnounceResponseMessage;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The announce response message from an HTTP tracker.
 *
 * @author mpetazzoni
 */
public class HTTPAnnounceResponseMessage extends HTTPTrackerMessage
        implements AnnounceResponseMessage {

    private static final Logger LOG = LoggerFactory.getLogger(HTTPAnnounceResponseMessage.class);
    public static final String EXTERNAL_IP = "external ip";
    public static final String INTERVAL = "interval";
    public static final String COMPLETE = "complete";
    public static final String INCOMPLETE = "incomplete";
    public static final String PEERS = "peers";
    public static final String PEERS6 = "peers6";
    public static final String PEER_IP = "ip";
    public static final String PEER_PORT = "port";
    public static final String PEER_ID = "peer id";
    private final InetAddress clientAddress;
    private final int interval;
    private final int complete;
    private final int incomplete;
    private final List<? extends Peer> peers;

    public HTTPAnnounceResponseMessage(
            @CheckForNull InetAddress clientAddress,
            int interval, int complete, int incomplete,
            @Nonnull List<? extends Peer> peers) {
        this.clientAddress = clientAddress;
        this.interval = interval;
        this.complete = complete;
        this.incomplete = incomplete;
        this.peers = peers;
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
    public Collection<? extends Peer> getPeers() {
        return peers;
    }

    @Override
    public Collection<? extends SocketAddress> getPeerAddresses() {
        return Collections2.transform(getPeers(), PEERADDRESS);
    }

    @Nonnull
    public static HTTPAnnounceResponseMessage fromBEValue(@Nonnull Map<String, BEValue> params)
            throws IOException, MessageValidationException {

        if (params.get(INTERVAL) == null) {
            throw new MessageValidationException(
                    "Tracker message missing mandatory field 'interval'!");
        }

        try {
            byte[] clientAddressBytes = BEUtils.getBytes(params.get(EXTERNAL_IP));
            InetAddress clientAddress = null;
            if (clientAddressBytes != null)
                clientAddress = InetAddress.getByAddress(clientAddressBytes);

            List<Peer> peers = new ArrayList<Peer>();

            BEValue peers4 = params.get(PEERS);
            if (peers4 == null) {
            } else if (peers4.getValue() instanceof List) {
                toPeerList(peers, peers4.getList());
            } else if (peers4.getValue() instanceof byte[]) {
                toPeerList(peers, peers4.getBytes(), 4);
            }

            BEValue peers6 = params.get(PEERS6);
            if (peers6 == null) {
            } else if (peers6.getValue() instanceof byte[]) {
                toPeerList(peers, peers6.getBytes(), 16);
            }

            return new HTTPAnnounceResponseMessage(
                    clientAddress,
                    BEUtils.getInt(params.get(INTERVAL), 60),
                    BEUtils.getInt(params.get(COMPLETE), 0),
                    BEUtils.getInt(params.get(INCOMPLETE), 0),
                    peers);
        } catch (InvalidBEncodingException ibee) {
            throw new MessageValidationException("Invalid response "
                    + "from tracker!", ibee);
        } catch (UnknownHostException uhe) {
            throw new MessageValidationException("Invalid peer "
                    + "in tracker response!", uhe);
        }
    }

    /**
     * Build a peer list as a list of {@link Peer}s from the
     * announce response's peer list (in non-compact mode).
     *
     * @param peers The list of {@link BEValue}s dictionaries describing the
     * peers from the announce response.
     * @return A {@link List} of {@link Peer}s representing the
     * peers' addresses. Peer IDs are lost, but they are not crucial.
     */
    @Nonnull
    private static void toPeerList(@Nonnull List<Peer> out, @Nonnull List<BEValue> peers) {
        for (BEValue peer : peers) {
            try {
                Map<String, BEValue> peerInfo = peer.getMap();
                String ip = BEUtils.getString(peerInfo.get(PEER_IP));
                int port = BEUtils.getInt(peerInfo.get(PEER_PORT), -1);
                if (ip == null || port < 0) {
                    LOG.warn("Invalid peer " + peer);
                    continue;
                }
                InetAddress inaddr = InetAddresses.forString(ip);
                InetSocketAddress saddr = new InetSocketAddress(inaddr, port);

                byte[] peerId = BEUtils.getBytes(peerInfo.get(PEER_ID));
                out.add(new Peer(saddr, peerId));
            } catch (InvalidBEncodingException e) {
                LOG.error("Failed to parse peer from " + peer, e);
            } catch (NullPointerException e) {
                LOG.error("Failed to parse peer from " + peer, e);
            } catch (IllegalArgumentException e) {
                LOG.error("Failed to parse peer from " + peer, e);
            }
        }
    }

    /**
     * Build a peer list as a list of {@link Peer}s from the
     * announce response's binary compact peer list.
     *
     * @param data The bytes representing the compact peer list from the
     * announce response.
     * @return A {@link List} of {@link Peer}s representing the
     * peers' addresses. Peer IDs are lost, but they are not crucial.
     */
    @Nonnull
    private static void toPeerList(List<Peer> out, @Nonnull byte[] data, int addrlen)
            throws InvalidBEncodingException, UnknownHostException {
        if (data.length % (addrlen + 2) != 0) {
            throw new InvalidBEncodingException(
                    "Invalid peers binary information string!");
        }

        int addrcount = data.length / (addrlen + 2);
        ByteBuffer peers = ByteBuffer.wrap(data);

        byte[] ipBytes = new byte[addrlen];
        for (int i = 0; i < addrcount; i++) {
            peers.get(ipBytes);
            int port = peers.getShort() & 0xFFFF;
            try {
                InetAddress ip = InetAddress.getByAddress(ipBytes);
                out.add(new Peer(new InetSocketAddress(ip, port), null));
            } catch (IllegalArgumentException e) {
                LOG.error("Failed to parse peer from " + Arrays.toString(ipBytes) + ", " + port, e);
            }
        }
    }

    @Nonnull
    public Map<String, BEValue> toBEValue(boolean compact, boolean noPeerIds) {
        Map<String, BEValue> params = new HashMap<String, BEValue>();
        // TODO: "min interval", "tracker id"
        if (Peer.isValidIpAddress(clientAddress))
            params.put(EXTERNAL_IP, new BEValue(clientAddress.getAddress()));
        params.put(INTERVAL, new BEValue(interval));
        params.put(COMPLETE, new BEValue(complete));
        params.put(INCOMPLETE, new BEValue(incomplete));

        if (compact) {
            ByteBuffer peer4Data = ByteBuffer.allocate(peers.size() * 6);
            ByteBuffer peer6Data = ByteBuffer.allocate(peers.size() * 18);

            for (Peer peer : peers) {
                LOG.info("Adding peer " + peer);
                byte[] ip = peer.getIpBytes();
                if (ip == null)
                    continue;
                if (ip.length == 4) {
                    peer4Data.put(ip);
                    peer4Data.putShort((short) peer.getPort());
                } else if (ip.length == 16) {
                    peer6Data.put(ip);
                    peer6Data.putShort((short) peer.getPort());
                } else {
                    LOG.warn("Cannot encode peer " + peer);
                }
            }

            if (peer4Data.position() > 0) {
                byte[] buf = Arrays.copyOf(peer4Data.array(), peer4Data.position());
                params.put(PEERS, new BEValue(buf));
            }

            if (peer6Data.position() > 0) {
                byte[] buf = Arrays.copyOf(peer6Data.array(), peer6Data.position());
                params.put(PEERS6, new BEValue(buf));
            }
        } else {
            List<BEValue> peerList = new ArrayList<BEValue>();

            for (Peer peer : peers) {
                LOG.info("Adding peer " + peer);

                Map<String, BEValue> peerItem = new HashMap<String, BEValue>();
                byte[] peerId = peer.getPeerId();
                if (peerId != null)
                    peerItem.put(PEER_ID, new BEValue(peerId));
                String ip = peer.getIpString();
                if (ip != null)
                    peerItem.put(PEER_IP, new BEValue(ip, Torrent.BYTE_ENCODING));
                int port = peer.getPort();
                if (port != -1)
                    peerItem.put(PEER_PORT, new BEValue(port));
                peerList.add(new BEValue(peerItem));
            }

            params.put(PEERS, new BEValue(peerList));
        }

        return params;
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("clientAddress", clientAddress)
                .add("interval", getInterval())
                .add("complete", getComplete())
                .add("incomplete", getIncomplete())
                .add("peers", getPeers())
                .toString();
    }
}