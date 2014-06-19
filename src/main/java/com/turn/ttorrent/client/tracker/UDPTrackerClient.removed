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
package com.turn.ttorrent.client.announce;

import com.turn.ttorrent.client.ClientEnvironment;
import com.turn.ttorrent.client.SharedTorrent;
import com.turn.ttorrent.common.Peer;
import com.turn.ttorrent.common.Torrent;
import com.turn.ttorrent.common.protocol.TrackerMessage;
import com.turn.ttorrent.common.protocol.TrackerMessage.*;
import com.turn.ttorrent.common.protocol.udp.*;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.Arrays;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Announcer for UDP trackers.
 *
 * <p>
 * The UDP tracker protocol requires a two-step announce request/response
 * exchange where the peer is first required to establish a "connection"
 * with the tracker by sending a connection request message and retreiving
 * a connection ID from the tracker to use in the following announce
 * request messages (valid for 2 minutes).
 * </p>
 *
 * <p>
 * It also contains a backing-off retry mechanism (on a 15*2^n seconds
 * scheme), in which if the announce request times-out for more than the
 * connection ID validity period, another connection request/response
 * exchange must be made before attempting to retransmit the announce
 * request.
 * </p>
 *
 * @author mpetazzoni
 */
public class UDPTrackerClient extends TrackerClient {

    protected static final Logger logger =
            LoggerFactory.getLogger(UDPTrackerClient.class);
    /**
     * Back-off timeout uses 15 * 2 ^ n formula.
     */
    private static final int UDP_BASE_TIMEOUT_SECONDS = 15;
    /**
     * We don't try more than 8 times (3840 seconds, as per the formula defined
     * for the backing-off timeout.
     *
     * @see #UDP_BASE_TIMEOUT_SECONDS
     */
    private static final int UDP_MAX_TRIES = 8;
    /**
     * For STOPPED announce event, we don't want to be bothered with waiting
     * that long. We'll try once and bail-out early.
     */
    private static final int UDP_MAX_TRIES_ON_STOPPED = 1;
    /**
     * Maximum UDP packet size expected, in bytes.
     *
     * The biggest packet in the exchange is the announce response, which in 20
     * bytes + 6 bytes per peer. Common numWant is 50, so 20 + 6 * 50 = 320.
     * With headroom, we'll ask for 512 bytes.
     */
    private static final int UDP_PACKET_LENGTH = 512;

    private enum State {

        CONNECT_REQUEST,
        ANNOUNCE_REQUEST;
        // TODO: Failed (or similar) state.
    };

    private static class UDPTorrentId {

        private final byte[] infoHash;

        public UDPTorrentId(@Nonnull byte[] infoHash) {
            this.infoHash = infoHash;
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(infoHash);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (null == obj)
                return false;
            if (!getClass().equals(obj.getClass()))
                return false;
            UDPTorrentId other = (UDPTorrentId) obj;
            return Arrays.equals(infoHash, other.infoHash);
        }

        @Override
        public String toString() {
            return Torrent.byteArrayToHexString(infoHash);
        }
    }

    private static class UDPTorrentState {

        private AnnounceRequestMessage.RequestEvent event;
    }

    private class UDPTrackerState {

        private final InetSocketAddress address;
        private final SharedTorrent torrent;
        private State state = State.CONNECT_REQUEST;
        private long connectionId;
        private long connectionExpiration;
        private int attempt = 0;
        private int transactionId;
        private final Map<UDPTorrentId, UDPTorrentState> torrents = new HashMap<UDPTorrentId, UDPTorrentState>();

        public UDPTrackerState(InetSocketAddress address, SharedTorrent torrent) {
            this.address = address;
            this.torrent = torrent;
        }

        public void setEvent(@Nonnull AnnounceRequestMessage.RequestEvent event) {
            this.event = event;
            this.attempt = 0;
        }

        public int getTimeout() {
            return UDP_BASE_TIMEOUT_SECONDS * (int) Math.pow(2, attempt);
        }

        public void run() {
            if (attempt++ > getMaxAttempts(event)) {
                logger.error("Timeout while announcing"
                        + formatAnnounceEvent(event) + " to tracker!");
                announcements.remove(address);
                return;
            }

            transactionId = environment.getRandom().nextInt();
            announcements.put(address, this);

            // Immediately decide if we can send the announce request
            // directly or not. For this, we need a valid, non-expired
            // connection ID.
            if (connectionExpiration > System.currentTimeMillis())
                state = State.ANNOUNCE_REQUEST;
            else
                logger.debug("Announce connection ID expired, "
                        + "reconnecting with tracker...");

            switch (state) {
                case CONNECT_REQUEST:
                    send(address, new UDPConnectRequestMessage(transactionId));
                    break;

                case ANNOUNCE_REQUEST:
                    send(address, new UDPAnnounceRequestMessage(
                            connectionId,
                            transactionId,
                            torrent.getInfoHash(),
                            peer.getPeerId(),
                            torrent.getDownloaded(),
                            torrent.getUploaded(),
                            torrent.getLeft(),
                            event,
                            peer.getAddress().getAddress(),
                            0,
                            TrackerMessage.AnnounceRequestMessage.DEFAULT_NUM_WANT,
                            peer.getAddress().getPort()));
                    break;

                default:
                    throw new IllegalStateException("Invalid announce state!");
            }

            // Mark for retry.
        }

        private void recv(UDPTrackerMessage.UDPTrackerResponseMessage message) {
            if (message.getTransactionId() != transactionId) {
                // Probably ignore silently: It's a delayed message after a timeout.
                logger.warn("Transaction id mismatch: " + message + " for " + this);
                return;
            }

            if (message instanceof UDPConnectResponseMessage) {
                UDPConnectResponseMessage response = (UDPConnectResponseMessage) message;
                connectionId = response.getConnectionId();
                connectionExpiration = System.currentTimeMillis() + 60;
                run();
            } else if (message instanceof UDPAnnounceResponseMessage) {
                handleTrackerAnnounceResponse(null, message, false);
            } else if (message instanceof UDPTrackerErrorMessage) {
                UDPTrackerErrorMessage response = (UDPTrackerErrorMessage) message;
                logger.warn("Announce failed: " + response.getReason());
            } else {
                logger.error("Unknown UDP message " + message);
            }
        }
    }
    private final ConcurrentMap<InetSocketAddress, UDPTrackerState> announcements = new ConcurrentHashMap<InetSocketAddress, UDPTrackerState>();
    private DatagramChannel channel;

    /**
     * 
     * @param torrent
     */
    public UDPTrackerClient(ClientEnvironment environment, Peer peer) {
        super(environment, peer);

        InetSocketAddress address = peer.getAddress();
        if (!(address.getAddress() instanceof Inet4Address))
            throw new UnsupportedOperationException("UDP announce only supports IPv4, see http://bittorrent.org/beps/bep_0015.html#ipv6");
    }

    @Override
    public void start() throws Exception {
        super.start();
        channel = DatagramChannel.open();
        channel.configureBlocking(false);
        channel.bind(peer.getAddress());
    }

    /**
     * Close this announce connection.
     */
    @Override
    public void stop() throws Exception {
        if (channel != null && channel.isOpen())
            channel.close();
        channel = null;
        super.stop();
    }

    private static int getMaxAttempts(AnnounceRequestMessage.RequestEvent event) {
        return AnnounceRequestMessage.RequestEvent.STOPPED.equals(event)
                ? UDP_MAX_TRIES_ON_STOPPED
                : UDP_MAX_TRIES;
    }

    @Override
    public void announce(
            AnnounceResponseListener listener,
            SharedTorrent torrent, URI tracker,
            AnnounceRequestMessage.RequestEvent event, boolean inhibitEvents) throws AnnounceException {
        logger.info("Announcing{} to tracker with {}U/{}D/{}L bytes...",
                new Object[]{
            formatAnnounceEvent(event),
            torrent.getUploaded(),
            torrent.getDownloaded(),
            torrent.getLeft()
        });

        InetSocketAddress address = new InetSocketAddress(tracker.getHost(), tracker.getPort());
        UDPAnnounceName name = new UDPAnnounceName(address, torrent.getInfoHash());
        UDPTrackerState state = new UDPTrackerState(address, torrent);
        {
            UDPTrackerState state0 = announcements.putIfAbsent(name, state);
            if (state0 != null)
                state = state0;
        }

        state.setEvent(event);
        state.run();
    }

    /**
     * Send a UDP packet to the tracker.
     *
     * @param data The {@link ByteBuffer} to send in a datagram packet to the
     * tracker.
     */
    private void send(InetSocketAddress destination, UDPTrackerMessage message) {
        try {
            ByteBuf buf = Unpooled.buffer(UDP_PACKET_LENGTH);
            message.toWire(buf);
            if (channel.send(buf.nioBuffer(), destination) < buf.readableBytes())
                logger.warn("Sent short datagram to tracker at {}", destination);
        } catch (IOException ioe) {
            logger.warn("Error sending datagram packet to tracker at {}: {}.",
                    destination, ioe.getMessage());
        }
    }

    /**
     * Receive a UDP packet from the tracker.
     *
     * @param attempt The attempt number, used to calculate the timeout for the
     * receive operation.
     * @return Returns a {@link ByteBuffer} containing the packet data.
     */
    private void recv()
            throws IOException, MessageValidationException {
        ByteBuffer buffer = ByteBuffer.allocate(UDP_PACKET_LENGTH);
        SocketAddress address = channel.receive(buffer);
        ByteBuf buf = Unpooled.wrappedBuffer(buffer);
        UDPTrackerMessage.UDPTrackerResponseMessage message = UDPTrackerMessage.UDPTrackerResponseMessage.parse(buf);
        UDPAnnounceId id = new UDPAnnounceId(address, message.getTransactionId());
        UDPTrackerState state = announcements.remove(id);
        state.recv(message);
    }
}
