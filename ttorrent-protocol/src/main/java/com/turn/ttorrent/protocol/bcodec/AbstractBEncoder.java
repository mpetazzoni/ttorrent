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
package com.turn.ttorrent.protocol.bcodec;

import com.google.common.base.Charsets;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;

/**
 * B-encoding encoder.
 *
 * <p>
 * This class provides utility methods to encode objects and
 * {@link BEValue}s to B-encoding into a provided output stream.
 * </p>
 *
 * @see <a href="http://en.wikipedia.org/wiki/Bencode">B-encoding specification</a>
 * @author shevek
 */
public abstract class AbstractBEncoder {

    protected abstract void writeByte(int b)
            throws IOException;

    protected abstract void writeBytes(@Nonnull byte[] b)
            throws IOException;

    @SuppressWarnings("unchecked")
    public void bencode(@Nonnull Object o)
            throws IOException {
        if (o instanceof BEValue)
            o = ((BEValue) o).getValue();

        if (o instanceof String) {
            bencode((String) o);
        } else if (o instanceof byte[]) {
            bencode((byte[]) o);
        } else if (o instanceof Number) {
            bencode((Number) o);
        } else if (o instanceof List) {
            bencode((List<BEValue>) o);
        } else if (o instanceof Map) {
            bencode((Map<String, BEValue>) o);
        } else {
            throw new IllegalArgumentException("Cannot bencode: " + o.getClass());
        }
    }

    public void bencode(@Nonnull String s)
            throws IOException {
        byte[] b = s.getBytes(Charsets.UTF_8);
        bencode(b);
    }

    public void bencode(@Nonnull Number n)
            throws IOException {
        writeByte('i');
        String s = n.toString();
        writeBytes(s.getBytes(Charsets.UTF_8));
        writeByte('e');
    }

    public void bencode(@Nonnull List<BEValue> l)
            throws IOException {
        writeByte('l');
        for (BEValue value : l)
            bencode(value);
        writeByte('e');
    }

    public void bencode(@Nonnull byte[] b)
            throws IOException {
        String l = Integer.toString(b.length);
        writeBytes(l.getBytes(Charsets.UTF_8));
        writeByte(':');
        writeBytes(b);
    }

    public void bencode(@Nonnull Map<String, BEValue> m)
            throws IOException {
        writeByte('d');
        // Keys must be sorted.
        List<String> keys = new ArrayList<String>(m.keySet());
        Collections.sort(keys);
        for (String key : keys) {
            bencode(key);
            bencode(m.get(key));
        }
        writeByte('e');
    }
}