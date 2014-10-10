/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.test;

import java.util.Random;
import org.apache.commons.io.input.NullInputStream;

/**
 *
 * @author shevek
 */
public class RandomInputStream extends NullInputStream {

    private final Random r = new Random(1234);

    public RandomInputStream(long size) {
        super(size);
    }

    @Override
    protected int processByte() {
        return r.nextInt() & 0xFF;
    }

    @Override
    protected void processBytes(byte[] bytes, int offset, int length) {
        if (offset == 0 && length == bytes.length) {
            r.nextBytes(bytes);
        } else {
            byte[] tmp = new byte[length];
            r.nextBytes(tmp);
            System.arraycopy(tmp, 0, bytes, offset, length);
        }
    }
}
