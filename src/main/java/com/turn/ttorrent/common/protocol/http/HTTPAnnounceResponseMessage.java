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

import com.google.common.collect.Collections2;
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
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    public static final String PEER_IP = "ip";
    public static final String PEER_PORT = "port";
    public static final String PEER_ID = "peer id";
    private final byte[] clientAddress;
    private final int interval;
    private final int complete;
    private final int incomplete;
    private final List<? extends Peer> peers;

    public HTTPAnnounceResponseMessage(
            @Nonnull byte[] clientAddress,
            int interval, int complete, int incomplete,
            @Nonnull List<? extends Peer> peers) {
        super(Type.ANNOUNCE_RESPONSE);
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

    public static HTTPAnnounceResponseMessage fromBEValue(Map<String, BEValue> params)
            throws IOException, MessageValidationException {

        if (params.get(INTERVAL) == null) {
            throw new MessageValidationException(
                    "Tracker message missing mandatory field 'interval'!");
        }

        try {
            List<Peer> peers;

            try {
                // First attempt to decode a compact response, since we asked
                // for it.
                peers = toPeerList(params.get(PEERS).getBytes());
            } catch (InvalidBEncodingException e) {
                // Fall back to peer list, non-compact response, in case the
                // tracker did not support compact responses.
                peers = toPeerList(params.get(PEERS).getList());
            }

            return new HTTPAnnounceResponseMessage(
                    BEUtils.getBytes(params.get(EXTERNAL_IP)),
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
    private static List<Peer> toPeerList(@Nonnull List<BEValue> peers)
            throws InvalidBEncodingException {
        List<Peer> result = new ArrayList<Peer>(peers.size());

        for (BEValue peer : peers) {
            Map<String, BEValue> peerInfo = peer.getMap();
            String ip = peerInfo.get(PEER_IP).getString(Torrent.BYTE_ENCODING);
            int port = peerInfo.get(PEER_PORT).getInt();
            byte[] peerId = BEUtils.getBytes(peerInfo.get(PEER_ID));
            InetSocketAddress address = new InetSocketAddress(ip, port);
            result.add(new Peer(address, peerId));
        }

        return result;
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
    private static List<Peer> toPeerList(@Nonnull byte[] data)
            throws InvalidBEncodingException, UnknownHostException {
        if (data.length % 6 != 0) {
            throw new InvalidBEncodingException(
                    "Invalid peers binary information string!");
        }

        List<Peer> result = new ArrayList<Peer>(data.length / 6);
        ByteBuffer peers = ByteBuffer.wrap(data);

        for (int i = 0; i < data.length / 6; i++) {
            byte[] ipBytes = new byte[4];
            peers.get(ipBytes);
            InetAddress ip = InetAddress.getByAddress(ipBytes);
            int port =
                    (0xFF & (int) peers.get()) << 8
                    | (0xFF & (int) peers.get());
            result.add(new Peer(new InetSocketAddress(ip, port), null));
        }

        return result;
    }

    @Nonnull
    public Map<String, BEValue> toBEValue(boolean compact) {
        Map<String, BEValue> params = new HashMap<String, BEValue>();
        // TODO: "min interval", "tracker id"
        if (Peer.isValidIpAddress(clientAddress))
            params.put(EXTERNAL_IP, new BEValue(clientAddress));
        params.put(INTERVAL, new BEValue(interval));
        params.put(COMPLETE, new BEValue(complete));
        params.put(INCOMPLETE, new BEValue(incomplete));

        // TODO: Fully support BEP#0023: Allow noncompact rep.
        ByteBuffer data = ByteBuffer.allocate(peers.size() * 6);
        for (Peer peer : peers) {
            // LOG.info("Adding peer " + peer);
            byte[] ip = peer.getIpBytes();
            if (ip == null || ip.length != 4)
                continue;
            data.put(ip);
            data.putShort((short) peer.getPort());
        }

        // LOG.info("Peers are " + Arrays.toString(data.array()));
        params.put(PEERS, new BEValue(data.array()));
        return params;
    }
}