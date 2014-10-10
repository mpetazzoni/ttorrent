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

import com.google.common.annotations.VisibleForTesting;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;

/**
 *
 * @author shevek
 */
public class PeerFrameDecoder extends LengthFieldBasedFrameDecoder {

    public PeerFrameDecoder() {
        super(Integer.MAX_VALUE, 0, PeerMessage.MESSAGE_LENGTH_FIELD_SIZE, 0, 4);
    }

    @VisibleForTesting
    /* pp */ ByteBuf _decode(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
        return (ByteBuf) super.decode(ctx, in);
    }
}
