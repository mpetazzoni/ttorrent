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

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import org.apache.commons.io.IOUtils;

/**
 *
 * @author shevek
 */
public class StreamBDecoder extends AbstractBDecoder {

    private final InputStream in;

    public StreamBDecoder(InputStream in) {
        this.in = in;
    }

    @Override
    protected byte readByte() throws IOException {
        int value = in.read();
        if (value == -1)
            throw new EOFException();
        return (byte) value;
    }

    @Override
    protected byte[] readBytes(int length) throws IOException {
        byte[] bytes = new byte[length];
        IOUtils.readFully(in, bytes);
        return bytes;
    }
}
