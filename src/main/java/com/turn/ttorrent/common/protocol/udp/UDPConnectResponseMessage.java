/**
 * Copyright (C) 2012 Turn, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.turn.ttorrent.common.protocol.udp;


import io.netty.buffer.ByteBuf;

/**
 * The connection response message for the UDP tracker protocol.
 *
 * @author mpetazzoni
 */
public class UDPConnectResponseMessage
        extends UDPTrackerMessage.UDPTrackerResponseMessage {

    private static final int UDP_CONNECT_RESPONSE_MESSAGE_SIZE = 16;
    private long connectionId;

    public UDPConnectResponseMessage() {
        super(Type.CONNECT_RESPONSE);
    }

    public long getConnectionId() {
        return this.connectionId;
    }

    public void setConnectionId(long connectionId) {
        this.connectionId = connectionId;
    }

    @Override
    public void fromWire(ByteBuf in) throws MessageValidationException {
        _fromWire(in, UDP_CONNECT_RESPONSE_MESSAGE_SIZE);
        setConnectionId(in.readLong());
    }

    @Override
    public void toWire(ByteBuf out) {
        _toWire(out);
        out.writeLong(connectionId);
    }
}