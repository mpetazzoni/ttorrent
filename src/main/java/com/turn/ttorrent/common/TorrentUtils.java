/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.common;

import java.util.BitSet;
import javax.annotation.Nonnull;

/**
 *
 * @author shevek
 */
public class TorrentUtils {

    public void toBitString(@Nonnull StringBuilder buf, @Nonnull BitSet b, char c0, char c1) {
        int len = b.length();
        for (int i = 0; i < len; i++)
            buf.append(b.get(i) ? c1 : c0);
    }

    public void toBitString(@Nonnull StringBuilder buf, @Nonnull BitSet b) {
        toBitString(buf, b, '0', '1');
    }

    @Nonnull
    public String toBitString(@Nonnull BitSet b) {
        StringBuilder buf = new StringBuilder();
        toBitString(buf, b);
        return buf.toString();
    }
}
