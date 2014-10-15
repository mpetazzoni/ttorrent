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

import com.google.common.collect.Sets;
import com.turn.ttorrent.client.Client;
import com.turn.ttorrent.tracker.client.PeerAddressProvider;
import com.turn.ttorrent.protocol.TorrentUtils;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.util.Collections;
import java.util.Set;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author shevek
 */
public class PeerServer implements PeerAddressProvider {

    private static final Logger LOG = LoggerFactory.getLogger(PeerServer.class);
    public static final int PORT_RANGE_START = 6881;
    public static final int PORT_RANGE_END = 6999;
    public static final int CLIENT_KEEP_ALIVE_MINUTES = 3;
    private final Client client;
    @CheckForNull
    private final SocketAddress address;
    private ChannelFuture future;

    // This can stop taking Client if we sort out where the peerId will come from.
    // One option is to make PeerAddressProvider NOT extend PeerIdentityProvider.
    public PeerServer(@Nonnull Client client, @CheckForNull SocketAddress address) {
        this.client = client;
        this.address = address;
    }

    public void start() throws Exception {
        EventLoopGroup group = new NioEventLoopGroup();
        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(group);
        bootstrap.channel(NioServerSocketChannel.class);
        bootstrap.option(ChannelOption.SO_BACKLOG, 128);
        bootstrap.option(ChannelOption.SO_REUSEADDR, true);
        bootstrap.option(ChannelOption.TCP_NODELAY, true);
        bootstrap.childHandler(new PeerServerHandshakeHandler(client));
        bootstrap.childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);
        bootstrap.childOption(ChannelOption.SO_KEEPALIVE, true);
        // bootstrap.childOption(ChannelOption.SO_TIMEOUT, (int) TimeUnit.MINUTES.toMillis(CLIENT_KEEP_ALIVE_MINUTES));
        // bootstrap.childOption(ChannelOption.WRITE_BUFFER_HIGH_WATER_MARK, 1024 * 1024);
        // bootstrap.childOption(ChannelOption.WRITE_BUFFER_LOW_WATER_MARK, 8 * 1024);
        if (address != null) {
            future = bootstrap.bind(address).sync();
        } else {
            BIND:
            {
                Exception x = new IOException("No available port for the BitTorrent client!");
                for (int i = PORT_RANGE_START; i <= PORT_RANGE_END; i++) {
                    try {
                        future = bootstrap.bind(i).sync();
                        break BIND;
                    } catch (InterruptedException e) {
                        throw e;
                    } catch (Exception e) {
                        x = e;
                    }
                }
                throw new IOException("Failed to find an address to bind in range [" + PORT_RANGE_START + "," + PORT_RANGE_END + "]", x);
            }
        }
    }

    public void stop() throws InterruptedException {
        try {
            Channel channel = future.channel();
            channel.close().sync();
            channel.eventLoop().shutdownGracefully();
        } finally {
            future = null;
        }
    }

    @Override
    public byte[] getLocalPeerId() {
        return client.getEnvironment().getLocalPeerId();
    }

    @Override
    public String getLocalPeerName() {
        return client.getEnvironment().getLocalPeerName();
    }

    @Nonnull
    public InetSocketAddress getLocalAddress() {
        ServerSocketChannel channel = (ServerSocketChannel) future.channel();
        return channel.localAddress();
    }

    @Override
    public Set<? extends InetSocketAddress> getLocalAddresses() {
        try {
            // TODO: This call may be expensive on some operating systems. Cache it.
            return Sets.newHashSet(TorrentUtils.getSpecificAddresses(getLocalAddress()));
        } catch (SocketException e) {
            LOG.error("Failed to get specific addresses", e);
            return Collections.emptySet();
        }
    }
}
