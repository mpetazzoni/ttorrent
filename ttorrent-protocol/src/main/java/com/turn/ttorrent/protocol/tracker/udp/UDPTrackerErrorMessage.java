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
package com.turn.ttorrent.protocol.tracker.udp;

import com.turn.ttorrent.protocol.bcodec.BEUtils;
import com.turn.ttorrent.protocol.tracker.TrackerMessage;
import io.netty.buffer.ByteBuf;

/**
 * The error message for the UDP tracker protocol.
 *
 * @author mpetazzoni
 */
public class UDPTrackerErrorMessage
        extends UDPTrackerMessage.UDPTrackerResponseMessage
        implements TrackerMessage.ErrorMessage {

    private static final int UDP_TRACKER_ERROR_MIN_MESSAGE_SIZE = 8;
    private String reason;

    private UDPTrackerErrorMessage() {
        super(Type.ERROR);
    }

    @Override
    public String getReason() {
        return this.reason;
    }

    @Override
    public void fromWire(ByteBuf in) throws MessageValidationException {
        if (in.readableBytes() < UDP_TRACKER_ERROR_MIN_MESSAGE_SIZE)
            throw new MessageValidationException("Invalid tracker error message size " + in.readableBytes());
        _fromWire(in, -1);

        byte[] reasonBytes = new byte[in.readableBytes()];
        in.readBytes(reasonBytes);
        reason = new String(reasonBytes, BEUtils.BYTE_ENCODING);
    }

    @Override
    public void toWire(ByteBuf out) {
        _toWire(out);
        out.writeBytes(reason.getBytes(BEUtils.BYTE_ENCODING));
    }
}