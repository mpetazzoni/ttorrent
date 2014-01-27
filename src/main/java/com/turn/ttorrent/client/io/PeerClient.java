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

import com.turn.ttorrent.client.Client;
import com.turn.ttorrent.client.peer.PeerConnectionListener;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import java.net.SocketAddress;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author shevek
 */
public class PeerClient {

    private static final Logger LOG = LoggerFactory.getLogger(PeerClient.class);
    public static final int CLIENT_KEEP_ALIVE_MINUTES = PeerServer.CLIENT_KEEP_ALIVE_MINUTES;
    private final Client client;
    private EventLoopGroup group;
    private Bootstrap bootstrap;
    private final Object lock = new Object();

    public PeerClient(@Nonnull Client client) {
        this.client = client;
    }

    public void start() throws Exception {
        group = new NioEventLoopGroup();
        bootstrap = new Bootstrap();
        bootstrap.group(group);
        bootstrap.channel(NioSocketChannel.class);
        bootstrap.option(ChannelOption.SO_KEEPALIVE, true);
        bootstrap.option(ChannelOption.SO_REUSEADDR, true);
        bootstrap.option(ChannelOption.TCP_NODELAY, true);
        // bootstrap.option(ChannelOption.SO_TIMEOUT, (int) TimeUnit.MINUTES.toMillis(CLIENT_KEEP_ALIVE_MINUTES));
        bootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) TimeUnit.SECONDS.toMillis(10));
    }

    public void stop() throws InterruptedException {
        bootstrap = null;
        group.shutdownGracefully();
        group = null;
    }

    @Nonnull
    public ChannelFuture connect(
            @Nonnull final PeerConnectionListener listener,
            @Nonnull final byte[] infoHash,
            @Nonnull final SocketAddress remoteAddress) {
        final ChannelFuture future;
        synchronized (lock) {
            // connect -> initAndRegister grabs this, so we can safely synchronize here.
            bootstrap.handler(new PeerClientHandshakeHandler(listener, infoHash, client.getPeerId()));
            future = bootstrap.connect(remoteAddress);
        }
        future.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                try {
                    LOG.trace("Succeeded: {}", future.get());
                } catch (Exception e) {
                    LOG.error("Connection to " + remoteAddress + " failed.", e);
                    listener.handlePeerConnectionFailed(remoteAddress, e);
                }
            }
        });
        return future;
    }
}