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
import com.turn.ttorrent.client.peer.SharingPeer;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
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
        bootstrap.option(ChannelOption.SO_BACKLOG, 128);
        bootstrap.option(ChannelOption.SO_KEEPALIVE, true);
        bootstrap.option(ChannelOption.SO_TIMEOUT, (int) TimeUnit.MINUTES.toMillis(CLIENT_KEEP_ALIVE_MINUTES));
        bootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) TimeUnit.SECONDS.toMillis(10));
    }

    public void stop() throws InterruptedException {
        bootstrap = null;
        group.shutdownGracefully();
        group = null;
    }

    @Nonnull
    public ChannelFuture connect(@Nonnull SocketAddress remoteAddress,
            @Nonnull final SharingPeer peer,
            @Nonnull final PeerConnectionListener listener) {
        ChannelFuture future;
        synchronized (lock) {
            // connect -> initAndRegister grabs this, so we can safely synchronize here.
            bootstrap.handler(new PeerChannelInitializer(client) {
                @Override
                protected void initChannel(Channel ch) throws Exception {
                    ch.pipeline().addLast(new PeerClientHandshakeHandler(client, peer, listener));
                    super.initChannelPipeline(ch.pipeline(), peer);
                }
            });
            future = bootstrap.connect(remoteAddress);
        }
        future.addListener(new GenericFutureListener<Future<? super Void>>() {
            @Override
            public void operationComplete(Future<? super Void> future) throws Exception {
                try {
                    LOG.error("Succeeded: " + future.get());
                } catch (Exception e) {
                    LOG.error("Failed", e);
                }
            }
        });
        return future;
    }
}