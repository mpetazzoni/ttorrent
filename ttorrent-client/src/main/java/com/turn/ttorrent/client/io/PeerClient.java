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
package com.turn.ttorrent.client.io;

import com.turn.ttorrent.client.ClientEnvironment;
import com.turn.ttorrent.client.peer.PeerConnectionListener;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelOption;
import java.net.SocketAddress;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author shevek
 */
public class PeerClient extends PeerEndpoint {

    private static final Logger LOG = LoggerFactory.getLogger(PeerClient.class);
    public static final int CLIENT_KEEP_ALIVE_MINUTES = PeerServer.CLIENT_KEEP_ALIVE_MINUTES;
    private final ClientEnvironment environment;
    private Bootstrap bootstrap;
    private final Object lock = new Object();

    public PeerClient(ClientEnvironment environment) {
        this.environment = environment;
    }

    public void start() throws Exception {
        bootstrap = new Bootstrap();
        bootstrap.group(environment.getEventService());
        bootstrap.channel(environment.getEventLoopType().getClientChannelType());
        // SocketAddress address = environment.getLocalPeerListenAddress();
        // if (address != null) bootstrap.localAddress(address);
        bootstrap.option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);
        bootstrap.option(ChannelOption.SO_KEEPALIVE, true);
        bootstrap.option(ChannelOption.SO_REUSEADDR, true);
        bootstrap.option(ChannelOption.TCP_NODELAY, true);
        // bootstrap.option(ChannelOption.SO_TIMEOUT, (int) TimeUnit.MINUTES.toMillis(CLIENT_KEEP_ALIVE_MINUTES));
        bootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) TimeUnit.SECONDS.toMillis(10));
        // TODO: Derive from PieceHandler.DEFAULT_BLOCK_SIZE
        // bootstrap.option(ChannelOption.WRITE_BUFFER_HIGH_WATER_MARK, 1024 * 1024);
        // bootstrap.option(ChannelOption.WRITE_BUFFER_LOW_WATER_MARK, 8 * 1024);
    }

    public void stop() throws InterruptedException {
        bootstrap = null;
    }

    @Nonnull
    public ChannelFuture connect(
            @Nonnull final PeerConnectionListener listener,
            @Nonnull final byte[] infoHash,
            @Nonnull final SocketAddress remoteAddress) {
        final ChannelFuture future;
        synchronized (lock) {
            // connect -> initAndRegister grabs this, so we can safely synchronize here.
            bootstrap.handler(new PeerClientHandshakeHandler(listener, infoHash, listener.getLocalPeerId()));
            future = bootstrap.connect(remoteAddress);
        }
        future.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                try {
                    LOG.trace("Succeeded: {}", future.get());
                } catch (Exception e) {
                    // LOG.error("Connection to " + remoteAddress + " failed.", e);
                    listener.handlePeerConnectionFailed(remoteAddress, e);
                    Channel channel = future.channel();
                    if (channel.isOpen())
                        channel.close(); // .addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
                }
            }
        });
        return future;
    }
}