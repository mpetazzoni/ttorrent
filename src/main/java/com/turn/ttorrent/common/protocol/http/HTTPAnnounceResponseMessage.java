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
import com.turn.ttorrent.bcodec.BEValue;
import com.turn.ttorrent.bcodec.InvalidBEncodingException;
import com.turn.ttorrent.common.Peer;
import com.turn.ttorrent.common.Torrent;
import com.turn.ttorrent.common.protocol.TrackerMessage.AnnounceResponseMessage;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
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
    private final int interval;
    private final int complete;
    private final int incomplete;
    private final List<? extends Peer> peers;

    public HTTPAnnounceResponseMessage(
            int interval, int complete, int incomplete, List<? extends Peer> peers) {
        super(Type.ANNOUNCE_RESPONSE);
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
        return this.peers;
    }

    @Override
    public Collection<? extends SocketAddress> getPeerAddresses() {
        return Collections2.transform(getPeers(), PEERADDRESS);
    }

    public static HTTPAnnounceResponseMessage fromBEValue(Map<String, BEValue> params)
            throws IOException, MessageValidationException {

        if (params.get("interval") == null) {
            throw new MessageValidationException(
                    "Tracker message missing mandatory field 'interval'!");
        }

        try {
            List<Peer> peers;

            try {
                // First attempt to decode a compact response, since we asked
                // for it.
                peers = toPeerList(params.get("peers").getBytes());
            } catch (InvalidBEncodingException e) {
                // Fall back to peer list, non-compact response, in case the
                // tracker did not support compact responses.
                peers = toPeerList(params.get("peers").getList());
            }

            return new HTTPAnnounceResponseMessage(
                    params.get("interval").getInt(),
                    params.get("complete") != null ? params.get("complete").getInt() : 0,
                    params.get("incomplete") != null ? params.get("incomplete").getInt() : 0,
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
    private static List<Peer> toPeerList(List<BEValue> peers)
            throws InvalidBEncodingException {
        List<Peer> result = new ArrayList<Peer>(peers.size());

        for (BEValue peer : peers) {
            Map<String, BEValue> peerInfo = peer.getMap();
            String ip = peerInfo.get("ip").getString(Torrent.BYTE_ENCODING);
            int port = peerInfo.get("port").getInt();
            byte[] peerId = null;
            if (peerInfo.containsKey("peer id"))
                peerId = peerInfo.get("peer id").getBytes();
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
    private static List<Peer> toPeerList(byte[] data)
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
    public Map<String, BEValue> toBEValue() {
        Map<String, BEValue> params = new HashMap<String, BEValue>();
        // TODO: "min interval", "tracker id"
        params.put("interval", new BEValue(interval));
        params.put("complete", new BEValue(complete));
        params.put("incomplete", new BEValue(incomplete));

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
        params.put("peers", new BEValue(data.array()));
        return params;
    }

    /**
     * Craft a compact announce response message.
     *
     * @param interval
     * @param minInterval
     * @param trackerId
     * @param complete
     * @param incomplete
     * @param peers
     */
    public static HTTPAnnounceResponseMessage craft(int interval,
            int minInterval, String trackerId, int complete, int incomplete,
            List<Peer> peers) throws IOException, UnsupportedEncodingException {

        return new HTTPAnnounceResponseMessage(
                interval, complete, incomplete, peers);
    }
}
