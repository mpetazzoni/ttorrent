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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.annotation.Nonnull;

/**
 *
 * @author shevek
 */
public class BytesBEncoder extends AbstractBEncoder {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    @Override
    protected void writeByte(int b) {
        out.write(b);
    }

    @Override
    protected void writeBytes(byte[] b) throws IOException {
        out.write(b);
    }

    @Nonnull
    public byte[] toByteArray() {
        return out.toByteArray();
    }
}
