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
package com.turn.ttorrent.common.protocol.http;

import com.turn.ttorrent.bcodec.BEValue;
import com.turn.ttorrent.bcodec.InvalidBEncodingException;
import com.turn.ttorrent.common.Torrent;
import com.turn.ttorrent.common.protocol.TrackerMessage.ErrorMessage;

import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nonnull;

/**
 * An error message from an HTTP tracker.
 *
 * @author mpetazzoni
 */
public class HTTPTrackerErrorMessage extends HTTPTrackerMessage implements ErrorMessage {

    private final String reason;

    public HTTPTrackerErrorMessage(String reason) {
        super(Type.ERROR);
        this.reason = reason;
    }

    public HTTPTrackerErrorMessage(ErrorMessage.FailureReason reason) {
        this(reason.getMessage());
    }

    @Override
    public String getReason() {
        return this.reason;
    }

    @Nonnull
    public static HTTPTrackerErrorMessage fromBEValue(@Nonnull Map<String, BEValue> params)
            throws MessageValidationException {

        try {
            String reason = params.get("failure reason").getString(Torrent.BYTE_ENCODING);
            return new HTTPTrackerErrorMessage(reason);
        } catch (InvalidBEncodingException ibee) {
            throw new MessageValidationException("Invalid tracker error "
                    + "message!", ibee);
        }
    }

    @Nonnull
    public Map<String, BEValue> toBEValue() {
        Map<String, BEValue> params = new HashMap<String, BEValue>();
        params.put("failure reason", new BEValue(getReason(), Torrent.BYTE_ENCODING));
        return params;
    }
}