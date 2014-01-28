/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.test;

import org.apache.commons.io.input.NullInputStream;

/**
 *
 * @author shevek
 */
public class PatternInputStream extends NullInputStream {

    private long offset;

    public PatternInputStream(long size) {
        super(size);
    }

    @Override
    protected int processByte() {
        try {
            long word = offset >> 3;
            int index = (int) (offset & 0x7);
            return (int) (word >>> (Long.SIZE - (Byte.SIZE * index)));
        } finally {
            offset++;
        }
    }

    @Override
    protected void processBytes(byte[] bytes, int offset, int length) {
        for (int i = 0; i < length; i++)
            bytes[offset + i] = (byte) processByte();
    }
}
