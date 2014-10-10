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
package com.turn.ttorrent.tracker.client;

import com.turn.ttorrent.protocol.tracker.Peer;
import com.turn.ttorrent.protocol.tracker.InetSocketAddressComparator;
import com.turn.ttorrent.protocol.tracker.TrackerMessage;
import com.turn.ttorrent.protocol.tracker.TrackerMessage.*;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;

public abstract class TrackerClient {

    private final PeerAddressProvider peerAddressProvider;

    public TrackerClient(@Nonnull PeerAddressProvider peerAddressProvider) {
        this.peerAddressProvider = peerAddressProvider;
    }

    @Nonnull
    protected byte[] getLocalPeerId() {
        return peerAddressProvider.getLocalPeerId();
    }

    /** Returns a fresh, concrete list. */
    @Nonnull
    protected List<InetSocketAddress> getLocalPeerAddresses() {
        List<InetSocketAddress> out = new ArrayList<InetSocketAddress>();
        for (SocketAddress address : peerAddressProvider.getLocalAddresses())
            if (address instanceof InetSocketAddress)
                out.add((InetSocketAddress) address);
        Collections.sort(out, InetSocketAddressComparator.INSTANCE);
        return out;
    }

    /**
     * Build, send and process a tracker announce request.
     *
     * <p>
     * This function first builds an announce request for the specified event
     * with all the required parameters. Then, the request is made to the
     * tracker and the response analyzed.
     * </p>
     *
     * <p>
     * All registered {@link AnnounceResponseListener} objects are then fired
     * with the decoded payload.
     * </p>
     *
     * @param event The announce event type (can be AnnounceEvent.NONE for
     * periodic updates).
     * @param inhibitEvent Prevent event listeners from being notified.
     */
    public abstract void announce(
            AnnounceResponseListener listener,
            TorrentMetadataProvider torrent,
            URI tracker,
            TrackerMessage.AnnounceEvent event,
            boolean inhibitEvents) throws AnnounceException;

    public void start() throws Exception {
    }

    /**
     * Close any opened announce connection.
     *
     * <p>
     * This method is called by Client.stop() to make sure all connections
     * are correctly closed when the announce thread is asked to stop.
     * </p>
     */
    public void stop() throws Exception {
        // Do nothing by default, but can be overloaded.
    }

    /**
     * Formats an announce event into a usable string.
     */
    public static String formatAnnounceEvent(TrackerMessage.AnnounceEvent event) {
        return TrackerMessage.AnnounceEvent.NONE.equals(event)
                ? ""
                : String.format(" %s", event.name());
    }

    /**
     * Handle the announce response from the tracker.
     *
     * <p>
     * Analyzes the response from the tracker and acts on it. If the response
     * is an error, it is logged. Otherwise, the announce response is used
     * to fire the corresponding announce and peer events to all announce
     * listeners.
     * </p>
     *
     * @param message The incoming {@link TrackerMessage}.
     * @param inhibitEvents Whether or not to prevent events from being fired.
     */
    protected void handleTrackerAnnounceResponse(
            @Nonnull AnnounceResponseListener listener,
            @Nonnull URI tracker,
            @Nonnull TrackerMessage message, // AnnounceResponse or Error
            boolean inhibitEvents) throws AnnounceException {
        if (message instanceof ErrorMessage) {
            ErrorMessage error = (ErrorMessage) message;
            throw new AnnounceException(error.getReason());
        }

        if (!(message instanceof AnnounceResponseMessage)) {
            throw new AnnounceException("Unexpected tracker message " + message);
        }

        if (inhibitEvents) {
            return;
        }

        AnnounceResponseMessage response = (AnnounceResponseMessage) message;
        fireAnnounceResponseEvent(Arrays.asList(listener),
                tracker,
                response.getComplete(),
                response.getIncomplete(),
                TimeUnit.SECONDS.toMillis(response.getInterval()));
        Map<SocketAddress, byte[]> peers = new HashMap<SocketAddress, byte[]>();
        for (Peer peer : response.getPeers())
            peers.put(peer.getAddress(), peer.getPeerId());
        fireDiscoveredPeersEvent(Arrays.asList(listener),
                tracker,
                peers);
    }

    /**
     * Fire the announce response event to all listeners.
     *
     * @param complete The number of seeders on this torrent.
     * @param incomplete The number of leechers on this torrent.
     * @param interval The announce interval requested by the tracker.
     */
    protected static void fireAnnounceResponseEvent(
            @Nonnull Iterable<? extends AnnounceResponseListener> listeners,
            @Nonnull URI tracker,
            int complete, int incomplete, long interval) {
        for (AnnounceResponseListener listener : listeners) {
            listener.handleAnnounceResponse(tracker, interval, complete, incomplete);
        }
    }

    /**
     * Fire the new peer discovery event to all listeners.
     *
     * @param peers The list of peers discovered.
     */
    protected static void fireDiscoveredPeersEvent(
            @Nonnull Iterable<? extends AnnounceResponseListener> listeners,
            @Nonnull URI tracker,
            @Nonnull Map<? extends SocketAddress, ? extends byte[]> peers) {
        for (AnnounceResponseListener listener : listeners) {
            listener.handleDiscoveredPeers(tracker, peers);
        }
    }
}
