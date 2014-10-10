/**
 * Copyright (C) 2011-2012 Turn, Inc.
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
package com.turn.ttorrent.protocol.bcodec;

import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;

/**
 * A type-agnostic container for B-encoded values.
 *
 * @author mpetazzoni
 */
public class BEValue {

    /**
     * The B-encoded value can be a byte array, a Number, a List or a Map.
     * Lists and Maps contains BEValues too.
     */
    @Nonnull
    private final Object value;

    public BEValue(@Nonnull byte[] value) {
        this.value = Preconditions.checkNotNull(value);
    }

    public BEValue(@Nonnull String value) {
        this(value, Charsets.UTF_8);
    }

    public BEValue(@Nonnull String value, @Nonnull Charset enc) {
        this.value = value.getBytes(enc);
    }

    public BEValue(int value) {
        this.value = Integer.valueOf(value);
    }

    public BEValue(long value) {
        this.value = Long.valueOf(value);
    }

    public BEValue(@Nonnull Number value) {
        this.value = Preconditions.checkNotNull(value);
    }

    public BEValue(@Nonnull List<BEValue> value) {
        this.value = Preconditions.checkNotNull(value);
    }

    public BEValue(@Nonnull Map<String, BEValue> value) {
        this.value = Preconditions.checkNotNull(value);
    }

    @Nonnull
    public Object getValue() {
        return value;
    }

    /**
     * Returns this BEValue as a String, interpreted as UTF-8.
     * @throws InvalidBEncodingException If the value is not a byte[].
     */
    @Nonnull
    public String getString() throws InvalidBEncodingException {
        return getString(Charsets.UTF_8);
    }

    /**
     * Returns this BEValue as a String, interpreted with the specified
     * encoding.
     *
     * @param encoding The encoding to interpret the bytes as when converting
     * them into a {@link String}.
     * @throws InvalidBEncodingException If the value is not a byte[].
     */
    @Nonnull
    public String getString(Charset encoding) throws InvalidBEncodingException {
        return new String(getBytes(), encoding);
    }

    /**
     * Returns this BEValue as a byte[].
     *
     * @throws InvalidBEncodingException If the value is not a byte[].
     */
    public byte[] getBytes() throws InvalidBEncodingException {
        try {
            return (byte[]) value;
        } catch (ClassCastException cce) {
            throw new InvalidBEncodingException(cce);
        }
    }

    /**
     * Returns this BEValue as a Number.
     *
     * @throws InvalidBEncodingException  If the value is not a {@link Number}.
     */
    @Nonnull
    public Number getNumber() throws InvalidBEncodingException {
        try {
            return (Number) value;
        } catch (ClassCastException cce) {
            throw new InvalidBEncodingException(cce);
        }
    }

    /**
     * Returns this BEValue as short.
     *
     * @throws InvalidBEncodingException If the value is not a {@link Number}.
     */
    public short getShort() throws InvalidBEncodingException {
        return getNumber().shortValue();
    }

    /**
     * Returns this BEValue as int.
     *
     * @throws InvalidBEncodingException If the value is not a {@link Number}.
     */
    public int getInt() throws InvalidBEncodingException {
        return getNumber().intValue();
    }

    /**
     * Returns this BEValue as long.
     *
     * @throws InvalidBEncodingException If the value is not a {@link Number}.
     */
    public long getLong() throws InvalidBEncodingException {
        return getNumber().longValue();
    }

    /**
     * Returns this BEValue as a List of BEValues.
     *
     * @throws InvalidBEncodingException If the value is not a {@link List}.
     */
    @Nonnull
    @SuppressWarnings("unchecked")
    public List<BEValue> getList() throws InvalidBEncodingException {
        try {
            return (List<BEValue>) value;
        } catch (ClassCastException cce) {
            throw new InvalidBEncodingException(cce);
        }
    }

    /**
     * Returns this BEValue as a Map of String keys and BEValue values.
     *
     * @throws InvalidBEncodingException If the value is not a {@link Map}.
     */
    @Nonnull
    @SuppressWarnings("unchecked")
    public Map<String, BEValue> getMap() throws InvalidBEncodingException {
        try {
            return (Map<String, BEValue>) value;
        } catch (ClassCastException cce) {
            throw new InvalidBEncodingException(cce);
        }
    }
}
