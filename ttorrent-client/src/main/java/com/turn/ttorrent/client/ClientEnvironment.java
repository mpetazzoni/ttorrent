/*
 * Copyright 2014 shevek.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.turn.ttorrent.client;

import com.codahale.metrics.MetricRegistry;
import com.turn.ttorrent.client.io.PeerServer;
import com.turn.ttorrent.client.peer.Instrumentation;
import com.turn.ttorrent.protocol.PeerIdentityProvider;
import com.turn.ttorrent.protocol.SuppressWarnings;
import com.turn.ttorrent.protocol.torrent.TorrentCreator;
import com.turn.ttorrent.protocol.TorrentUtils;
import com.turn.ttorrent.protocol.bcodec.BEUtils;
import com.turn.ttorrent.tracker.client.PeerAddressProvider;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.DefaultThreadFactory;
import java.net.SocketAddress;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

/**
 * The ClientEnvironment is created by the Client and may be consumed by other beans.
 *
 * @author shevek
 */
public class ClientEnvironment implements PeerIdentityProvider {

    public static final String BITTORRENT_ID_PREFIX = "-TO0042-";
    private final Random random = new Random();
    private final byte[] peerId;
    private SocketAddress peerListenAddress;
    private MetricRegistry metricRegistry = new MetricRegistry();
    private ThreadPoolExecutor executorService;
    private EventLoopGroup eventService;
    private Instrumentation peerInstrumentation = new Instrumentation();

    public ClientEnvironment(@CheckForNull String peerName) {
        // String id = BITTORRENT_ID_PREFIX + UUID.randomUUID().toString().split("-")[4];
        byte[] tmp = new byte[20];  // Far too many, but who cares.
        random.nextBytes(tmp);
        String id = BITTORRENT_ID_PREFIX + (peerName != null ? peerName : "") + TorrentUtils.toHex(tmp);
        this.peerId = Arrays.copyOf(id.getBytes(BEUtils.BYTE_ENCODING), 20);

    }

    /**
     * Get this client's peer specification.
     */
    @Override
    @SuppressWarnings("EI_EXPOSE_REP")
    public byte[] getLocalPeerId() {
        return peerId;
    }

    @Override
    public String getLocalPeerName() {
        return TorrentUtils.toText(getLocalPeerId());
    }

    /**
     * You probably want {@link PeerServer#getLocalAddresses()}.
     *
     * @see PeerAddressProvider
     */
    @CheckForNull
    public SocketAddress getLocalPeerListenAddress() {
        return peerListenAddress;
    }

    public void setLocalPeerListenAddress(@CheckForNull SocketAddress peerListenAddress) {
        this.peerListenAddress = peerListenAddress;
    }

    @Nonnull
    public MetricRegistry getMetricRegistry() {
        return metricRegistry;
    }

    public void setMetricRegistry(@Nonnull MetricRegistry metricRegistry) {
        this.metricRegistry = metricRegistry;
    }

    public void start() throws Exception {
        {
            executorService = TorrentCreator.newExecutor(getLocalPeerName());
        }
        {
            ThreadFactory factory = new DefaultThreadFactory("bittorrent-event-" + getLocalPeerName(), true);
            eventService = new NioEventLoopGroup(0, factory);
        }
    }

    private void shutdown(@CheckForNull ExecutorService service) throws InterruptedException {
        if (service != null && !service.isShutdown()) {
            service.shutdown();
            service.awaitTermination(1, TimeUnit.SECONDS);
        }
    }

    /**
     * Closes this context.
     */
    public void stop() throws Exception {
        shutdown(eventService);
        eventService = null;
        shutdown(executorService);
        executorService = null;
    }

    @Nonnull
    public Random getRandom() {
        return random;
    }

    @Nonnull
    public ThreadPoolExecutor getExecutorService() {
        return executorService;
    }

    @Nonnull
    public EventLoopGroup getEventService() {
        return eventService;
    }

    @Nonnull
    public Instrumentation getInstrumentation() {
        return peerInstrumentation;
    }

    public void setInstrumentation(@Nonnull Instrumentation peerInstrumentation) {
        this.peerInstrumentation = peerInstrumentation;
    }
}
