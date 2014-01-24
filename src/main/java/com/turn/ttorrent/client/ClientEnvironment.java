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

import com.turn.ttorrent.common.Torrent;
import com.turn.ttorrent.common.TorrentCreator;
import io.netty.util.concurrent.DefaultThreadFactory;
import java.util.Arrays;
import java.util.Random;
import java.util.UUID;
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
    private ThreadPoolExecutor executorService;
    private ScheduledExecutorService schedulerService;

    public ClientEnvironment() {
        String id = BITTORRENT_ID_PREFIX + UUID.randomUUID().toString().split("-")[4];
        this.peerId = Arrays.copyOf(id.getBytes(Torrent.BYTE_ENCODING), 20);

    }

    /**
     * Get this client's peer specification.
     */
    @Nonnull
    public byte[] getPeerId() {
        return peerId;
    }

    public void start() {
        {
            executorService = TorrentCreator.newExecutor();
        }
        {
            ThreadFactory factory = new DefaultThreadFactory("bittorrent-scheduler", true);
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
}
