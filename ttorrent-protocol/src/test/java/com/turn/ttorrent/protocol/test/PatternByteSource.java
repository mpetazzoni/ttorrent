/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.protocol.test;

import com.google.common.io.ByteSource;
import java.io.IOException;
import java.io.InputStream;

/**
 *
 * @author shevek
 */
public class PatternByteSource extends ByteSource {

    private final long size;

    public PatternByteSource(long size) {
        this.size = size;
    }

    @Override
    public long size() throws IOException {
        return size;
    }

    @Override
    public InputStream openStream() throws IOException {
        return new PatternInputStream(size);
    }
}
