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
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.TimeUnit;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author shevek
 */
public class PeerServer {

    private static final Logger LOG = LoggerFactory.getLogger(PeerServer.class);
    public static final int PORT_RANGE_START = 6881;
    public static final int PORT_RANGE_END = 6889;
    public static final int CLIENT_KEEP_ALIVE_MINUTES = 3;
    private final Client client;
    @CheckForNull
    private final SocketAddress address;
    private EventLoopGroup group;
    private ChannelFuture future;

    public PeerServer(@Nonnull Client client, @CheckForNull SocketAddress address) {
        this.client = client;
        this.address = address;
    }

    public PeerServer(@Nonnull Client client) {
        this(client, null);
    }

    public void start() throws Exception {
        group = new NioEventLoopGroup();
        ServerBootstrap b = new ServerBootstrap();
        b.group(group);
        b.channel(NioServerSocketChannel.class);
        b.option(ChannelOption.SO_BACKLOG, 128);
        b.option(ChannelOption.SO_REUSEADDR, true);
        b.option(ChannelOption.TCP_NODELAY, true);
        b.childHandler(new PeerServerHandshakeHandler(client));
        b.childOption(ChannelOption.SO_KEEPALIVE, true);
        // b.childOption(ChannelOption.SO_TIMEOUT, (int) TimeUnit.MINUTES.toMillis(CLIENT_KEEP_ALIVE_MINUTES));
        if (address != null) {
            future = b.bind(address).sync();
        } else {
            BIND:
            {
                Exception x = new IOException("No available port for the BitTorrent client!");
                for (int i = PORT_RANGE_START; i <= PORT_RANGE_END; i++) {
                    try {
                        future = b.bind(i).sync();
                        break BIND;
                    } catch (InterruptedException e) {
                        throw e;
                    } catch (Exception e) {
                        x = e;
                    }
                }
                throw x;
            }
        }
    }

    public void stop() throws InterruptedException {
        try {
            future.channel().close().sync();
            group.shutdownGracefully();
        } finally {
            future = null;
        }
    }

    @Nonnull
    public InetSocketAddress getLocalAddress() {
        ServerSocketChannel channel = (ServerSocketChannel) future.channel();
        return channel.localAddress();
    }
}
