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
package com.turn.ttorrent.client.tracker;


import java.net.SocketAddress;

import java.net.URI;
import java.util.Collection;
import java.util.EventListener;
import javax.annotation.Nonnull;

/**
 * EventListener interface for objects that want to receive tracker responses.
 *
 * @author mpetazzoni
 */
public interface AnnounceResponseListener extends EventListener {

    public void handleAnnounceFailed(@Nonnull URI tracker, @Nonnull String reason);

    /**
     * Handle an announce response event.
     *
     * @param interval The announce interval requested by the tracker.
     * @param complete The number of seeders on this torrent.
     * @param incomplete The number of leechers on this torrent.
     */
    public void handleAnnounceResponse(@Nonnull URI tracker, long interval, int complete, int incomplete);

    /**
     * Handle the discovery of new peers.
     *
     * @param peers The list of peers discovered (from the announce response or
     * any other means like DHT/PEX, etc.).
     */
    public void handleDiscoveredPeers(@Nonnull URI tracker, @Nonnull Collection<? extends SocketAddress> peerAddresses);
}
