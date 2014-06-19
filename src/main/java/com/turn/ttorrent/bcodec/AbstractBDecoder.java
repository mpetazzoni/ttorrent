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
package com.turn.ttorrent.bcodec;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

/**
 * B-encoding decoder.
 *
 * <p>
 * A b-encoded byte stream can represent byte arrays, numbers, lists and maps
 * (dictionaries). This class implements a decoder of such streams into
 * {@link BEValue}s.
 * </p>
 *
 * @see <a href="http://en.wikipedia.org/wiki/Bencode">B-encoding specification</a>
 * @author shevek
 */
public abstract class AbstractBDecoder {

    protected abstract byte readByte()
            throws IOException;

    @Nonnull
    protected abstract byte[] readBytes(int length)
            throws IOException;

    @Nonnull
    public BEValue bdecode()
            throws IOException {
        // Cannot return null if called with false.
        BEValue value = bdecode(false);
        if (value == null)
            throw new NullPointerException("Unexpected null from bdecode(false)");
        return value;
    }

    @CheckForNull
    private BEValue bdecode(boolean end)
            throws IOException {
        byte b = readByte();
        switch (b) {
            case '0':
            case '1':
            case '2':
            case '3':
            case '4':
            case '5':
            case '6':
            case '7':
            case '8':
            case '9':
                return _bdecodeBytes(b);
            case 'i':
                return _bdecodeNumber();
            case 'l':
                return _bdecodeList();
            case 'd':
                return _bdecodeMap();
            case 'e':
                if (end)
                    return null;
                throw new InvalidBEncodingException("Unexpected ending indicator.");
            default:
                throw new InvalidBEncodingException("Unknown indicator '" + ((char) b) + "'");
        }
    }

    @Nonnull
    public BEValue bdecodeBytes()
            throws IOException {
        byte b = readByte();
        return _bdecodeBytes(b);
    }

    @Nonnull
    private BEValue _bdecodeBytes(byte b)
            throws IOException {
        int digits = 1;
        int length = b - '0';
        LENGTH:
        for (;;) {
            b = readByte();
            switch (b) {
                case '0':
                case '1':
                case '2':
                case '3':
                case '4':
                case '5':
                case '6':
                case '7':
                case '8':
                case '9':
                    length = (length * 10) + (b - '0');
                    digits++;
                    break;
                case ':':
                    if (digits == 0)
                        throw new InvalidBEncodingException("Length contained no digits.");
                    break LENGTH;
                default:
                    throw new InvalidBEncodingException("Colon expected, not '" + (char) b + "'");
            }
        }

        byte[] out = readBytes(length);
        return new BEValue(out);
    }

    @Nonnull
    public BEValue bdecodeNumber() throws IOException {
        byte b = readByte();
        if (b != 'i')
            throw new InvalidBEncodingException("Expected 'i', not " + (char) b + "'");
        return _bdecodeNumber();
    }

    @Nonnull
    private BEValue _bdecodeNumber()
            throws IOException {
        StringBuilder text = new StringBuilder(64);
        boolean negative = false;
        long value = 0;
        int digits = 0;
        DIGIT:
        for (;;) {
            byte b = readByte();
            switch (b) {
                case '0':
                case '1':
                case '2':
                case '3':
                case '4':
                case '5':
                case '6':
                case '7':
                case '8':
                case '9':
                    text.append((char) b);
                    value = (value * 10) + (b - '0');
                    digits++;
                    break;
                case '-':
                    if (digits > 0)
                        throw new InvalidBEncodingException("Negation must precede digits.");
                    text.append('-');
                    break;
                case 'e':
                    if (digits == 0)
                        throw new InvalidBEncodingException("Number contained no digits.");
                    break DIGIT;
                default:
                    throw new InvalidBEncodingException("Expected 'e' not '" + (char) b + "'");
            }
        }
        if (negative)
            value = -value;
        if (digits < 16) // Lazy overflow check.
            return new BEValue(Long.valueOf(value));
        return new BEValue(new BigInteger(text.toString()));
    }

    @Nonnull
    public BEValue bdecodeList() throws IOException {
        byte b = readByte();
        if (b != 'l')
            throw new InvalidBEncodingException("Expected 'l', not " + (char) b + "'");
        return _bdecodeList();
    }

    /**
     * Returns the next b-encoded value on the stream and makes sure it is a
     * list.
     *
     * @throws InvalidBEncodingException If it is not a list.
     */
    @Nonnull
    private BEValue _bdecodeList() throws IOException {
        List<BEValue> out = new ArrayList<BEValue>();
        for (;;) {
            BEValue value = bdecode(true);
            if (value == null)
                break;
            out.add(value);
        }
        return new BEValue(out);
    }

    @Nonnull
    public BEValue bdecodeMap() throws IOException {
        byte b = readByte();
        if (b != 'd')
            throw new InvalidBEncodingException("Expected 'd', not " + (char) b + "'");
        return _bdecodeMap();
    }

    /**
     * Returns the next b-encoded value on the stream and makes sure it is a
     * map (dictionary).
     *
     * @throws InvalidBEncodingException If it is not a map.
     */
    @Nonnull
    private BEValue _bdecodeMap() throws IOException {
        Map<String, BEValue> out = new HashMap<String, BEValue>();
        for (;;) {
            BEValue key = bdecode(true);
            if (key == null)
                break;
            BEValue value = bdecode(false);
            out.put(key.getString(), value);
        }
        return new BEValue(out);
    }
}