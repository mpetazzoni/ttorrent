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
package com.turn.ttorrent.protocol.tracker.http;

import com.google.common.collect.Multimap;
import com.turn.ttorrent.protocol.bcodec.BEUtils;
import com.turn.ttorrent.protocol.tracker.TrackerMessage;
import java.util.Collection;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base class for HTTP tracker messages.
 *
 * @author mpetazzoni
 */
public abstract class HTTPTrackerMessage extends TrackerMessage {

    private static final Logger LOG = LoggerFactory.getLogger(HTTPTrackerMessage.class);

    @CheckForNull
    protected static String toString(@Nonnull Multimap<String, String> params, @Nonnull String key, @CheckForNull ErrorMessage.FailureReason error) throws MessageValidationException {
        LOOKUP:
        {
            Collection<String> texts = params.get(key);
            if (texts == null)
                break LOOKUP;
            if (texts.isEmpty())
                break LOOKUP;
            String text = texts.iterator().next();
            if (text == null)
                break LOOKUP;
            return text;
        }
        if (error != null)
            throw new MessageValidationException("Invalid parameters " + params + ": " + error.getMessage());
        return null;
    }

    @CheckForNull
    protected static byte[] toBytes(@Nonnull Multimap<String, String> params, @Nonnull String key, @CheckForNull ErrorMessage.FailureReason error) throws MessageValidationException {
        String text = toString(params, key, error);
        if (text == null)
            return null;
        return text.getBytes(BEUtils.BYTE_ENCODING);
    }

    protected static int toInt(@Nonnull Multimap<String, String> params, @Nonnull String key, int unknown, @CheckForNull ErrorMessage.FailureReason error) throws MessageValidationException {
        try {
            String text = toString(params, key, error);
            if (text == null)
                return unknown;
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw new MessageValidationException(e.getMessage(), e);
        }
    }

    protected static long toLong(@Nonnull Multimap<String, String> params, @Nonnull String key, long unknown, @CheckForNull ErrorMessage.FailureReason error) throws MessageValidationException {
        try {
            String text = toString(params, key, error);
            if (text == null)
                return unknown;
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new MessageValidationException(e.getMessage(), e);
        }
    }

    protected static boolean toBoolean(@Nonnull Multimap<String, String> params, @Nonnull String key) throws MessageValidationException {
        return toInt(params, key, 0, null) != 0;
    }
}
