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

import com.google.common.base.Preconditions;
import com.google.common.collect.Sets;
import com.turn.ttorrent.client.ClientEnvironment;
import com.turn.ttorrent.client.TorrentRegistry;
import com.turn.ttorrent.tracker.client.PeerAddressProvider;
import com.turn.ttorrent.protocol.TorrentUtils;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.socket.ServerSocketChannel;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.util.Collections;
import java.util.Set;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Incoming peer connections service.
 *
 * <p>
 * Every BitTorrent client, BitTorrent being a peer-to-peer protocol, listens
 * on a port for incoming connections from other peers sharing the same
 * torrent.
 * </p>
 *
 * <p>
 * A PeerServer implements this service and starts a listening socket
 * in the first available port in the default BitTorrent client port range
 * 6881-6999. When a peer connects to it, it expects the BitTorrent handshake
 * message, parses it and replies with our own handshake.
 * </p>
 *
 * <p>
 * A PeerServer is a singleton per {@link Client}, and can be shared across
 * torrents and swarms.
 * </p>
 *
 * @author shevek
 */
public class PeerServer extends PeerEndpoint implements PeerAddressProvider {

    private static final Logger LOG = LoggerFactory.getLogger(PeerServer.class);
    public static final int PORT_RANGE_START = 6881;
    public static final int PORT_RANGE_END = 6999;
    public static final int CLIENT_KEEP_ALIVE_MINUTES = 3;
    @Nonnull
    private final ClientEnvironment environment;
    @Nonnull
    private final TorrentRegistry torrents;
    private ChannelFuture future;

    // This can stop taking Client if we sort out where the peerId will come from.
    // One option is to make PeerAddressProvider NOT extend PeerIdentityProvider.
    public PeerServer(@Nonnull ClientEnvironment environment, @Nonnull TorrentRegistry torrents) {
        this.environment = Preconditions.checkNotNull(environment, "ClientEnvironment was null.");
        this.torrents = Preconditions.checkNotNull(torrents, "TorrentRegistry was null.");
    }

    /**
     * Create and start a new listening service for our torrents, reporting
     * with our peer ID on the given address.
     *
     * <p>
     * This binds to the first available port in the client port range
     * PORT_RANGE_START to PORT_RANGE_END.
     * </p>
     */
    public void start() throws Exception {
        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(environment.getEventService());
        bootstrap.channel(environment.getEventLoopType().getServerChannelType());
        bootstrap.option(ChannelOption.SO_BACKLOG, 128);
        bootstrap.option(ChannelOption.SO_REUSEADDR, true);
        bootstrap.option(ChannelOption.TCP_NODELAY, true);
        bootstrap.childHandler(new ChannelInitializer() {
            @Override
            protected void initChannel(Channel ch) throws Exception {
                ch.pipeline().addLast(new PeerServerHandshakeHandler(torrents));
            }
        });
        bootstrap.childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);
        bootstrap.childOption(ChannelOption.SO_KEEPALIVE, true);
        // bootstrap.childOption(ChannelOption.SO_TIMEOUT, (int) TimeUnit.MINUTES.toMillis(CLIENT_KEEP_ALIVE_MINUTES));
        // TODO: Derive from PieceHandler.DEFAULT_BLOCK_SIZE
        // bootstrap.childOption(ChannelOption.WRITE_BUFFER_HIGH_WATER_MARK, 1024 * 1024);
        // bootstrap.childOption(ChannelOption.WRITE_BUFFER_LOW_WATER_MARK, 8 * 1024);
        SocketAddress address = environment.getLocalPeerListenAddress();
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
        } finally {
            future = null;
        }
    }

    @Override
    public byte[] getLocalPeerId() {
        return environment.getLocalPeerId();
    }

    @Override
    public String getLocalPeerName() {
        return environment.getLocalPeerName();
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
