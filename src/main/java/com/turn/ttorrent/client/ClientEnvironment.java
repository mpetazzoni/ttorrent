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

import com.turn.ttorrent.client.peer.Instrumentation;
import com.turn.ttorrent.common.SuppressWarnings;
import com.turn.ttorrent.common.Torrent;
import com.turn.ttorrent.common.TorrentCreator;
import com.turn.ttorrent.common.TorrentUtils;
import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.MetricsRegistry;
import io.netty.util.concurrent.DefaultThreadFactory;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

/**
 *
 * @author shevek
 */
public class ClientEnvironment {

    public static final String BITTORRENT_ID_PREFIX = "-TO0042-";
    private final Random random = new Random();
    private final byte[] peerId;
    private MetricsRegistry metricsRegistry = Metrics.defaultRegistry();
    private ThreadPoolExecutor executorService;
    private ScheduledExecutorService schedulerService;
    private Instrumentation peerInstrumentation = new Instrumentation();

    public ClientEnvironment(@CheckForNull String peerName) {
        // String id = BITTORRENT_ID_PREFIX + UUID.randomUUID().toString().split("-")[4];
        byte[] tmp = new byte[20];  // Far too many, but who cares.
        random.nextBytes(tmp);
        String id = BITTORRENT_ID_PREFIX + (peerName != null ? peerName : "") + TorrentUtils.toHex(tmp);
        this.peerId = Arrays.copyOf(id.getBytes(Torrent.BYTE_ENCODING), 20);

    }

    /**
     * Get this client's peer specification.
     */
    @Nonnull
    @SuppressWarnings("EI_EXPOSE_REP")
    public byte[] getLocalPeerId() {
        return peerId;
    }

    @Nonnull
    public String getLocalPeerName() {
        return TorrentUtils.toText(getLocalPeerId());
    }

    @Nonnull
    public MetricsRegistry getMetricsRegistry() {
        return metricsRegistry;
    }

    public void setMetricsRegistry(@Nonnull MetricsRegistry metricsRegistry) {
        this.metricsRegistry = metricsRegistry;
    }

    public void start() throws Exception {
        {
            executorService = TorrentCreator.newExecutor(getLocalPeerName());
        }
        {
            ThreadFactory factory = new DefaultThreadFactory("bittorrent-scheduler-" + getLocalPeerName(), true);
            schedulerService = new ScheduledThreadPoolExecutor(1, factory);
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
        shutdown(schedulerService);
        schedulerService = null;
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
    public ScheduledExecutorService getSchedulerService() {
        return schedulerService;
    }

    @Nonnull
    public Instrumentation getInstrumentation() {
        return peerInstrumentation;
    }

    public void setInstrumentation(@Nonnull Instrumentation peerInstrumentation) {
        this.peerInstrumentation = peerInstrumentation;
    }
}
