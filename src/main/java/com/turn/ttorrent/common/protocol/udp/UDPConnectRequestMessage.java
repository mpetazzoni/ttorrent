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

import com.turn.ttorrent.common.protocol.TrackerMessage;

import io.netty.buffer.ByteBuf;

/**
 * The connection request message for the UDP tracker protocol.
 *
 * @author mpetazzoni
 */
public class UDPConnectRequestMessage
        extends UDPTrackerMessage.UDPTrackerRequestMessage {

    private static final int UDP_CONNECT_REQUEST_MESSAGE_SIZE = 16;
    private static final long UDP_CONNECT_REQUEST_MAGIC = 0x41727101980L;

    public UDPConnectRequestMessage() {
        super(Type.CONNECT_REQUEST);
        setConnectionId(UDP_CONNECT_REQUEST_MAGIC);
    }

    public UDPConnectRequestMessage(int transactionId) {
        this();
        setTransactionId(transactionId);
    }

    @Override
    public void fromWire(ByteBuf in) throws MessageValidationException {
        _fromWire(in, UDP_CONNECT_REQUEST_MESSAGE_SIZE);
        if (getConnectionId() != UDP_CONNECT_REQUEST_MAGIC)
            throw new MessageValidationException("Packet contained bad ConnectionId: " + this);
    }

    @Override
    public void toWire(ByteBuf out) {
        _toWire(out);
    }
}