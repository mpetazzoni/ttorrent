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
import com.turn.ttorrent.client.peer.PeerMessageListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.CombinedChannelDuplexHandler;
import io.netty.handler.logging.LoggingHandler;
import javax.annotation.Nonnull;

/**
 *
 * @author shevek
 */
public abstract class PeerChannelInitializer extends ChannelInitializer {

    private static final PeerFrameEncoder frameEncoder = new PeerFrameEncoder();
    private static final LoggingHandler wireLogger = new LoggingHandler("peer-wire");
    private static final LoggingHandler messageLogger = new LoggingHandler("peer-message");
    private final Client client;

    public PeerChannelInitializer(@Nonnull Client client) {
        this.client = client;
    }

    public void initChannelPipeline(@Nonnull ChannelPipeline pipeline, @Nonnull PeerMessageListener listener) {
        pipeline.addLast(new CombinedChannelDuplexHandler(new PeerFrameDecoder(), frameEncoder));
        pipeline.addLast(wireLogger);
        pipeline.addLast(new PeerMessageCodec());
        pipeline.addLast(messageLogger);
        pipeline.addLast(new PeerMessageHandler(listener));
    }

    /*
     @Override
     protected void initChannel(Channel ch) throws Exception {
     // ch.pipeline().addLast(new PeerHandshakeHandler());
     ch.pipeline().addLast(new PeerServerHandshakeHandler(client));
     }
     */
}
