/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.common;

import java.util.BitSet;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import org.apache.commons.codec.binary.Hex;

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

    /**
     * Convert a byte string to a string containing an hexadecimal
     * representation of the original data.
     *
     * @param bytes The byte array to convert.
     */
    @Nonnull
    public static String toHex(@Nonnull byte[] data) {
        return new String(Hex.encodeHex(data, false));
    }

    @CheckForNull
    public static String toHexOrNull(@CheckForNull byte[] data) {
        if (data == null)
            return null;
        return toHex(data);
    }

    @Nonnull
    public static String toText(@Nonnull byte[] bytes) {
        StringBuilder buf = new StringBuilder();
        for (byte b : bytes) {
            if (Character.isValidCodePoint(b))
                buf.append((char) b);
            else
                buf.append("\\x").append((int) b);
        }
        return buf.toString();
    }

    @CheckForNull
    public static String toTextOrNull(@CheckForNull byte[] data) {
        if (data == null)
            return null;
        return toText(data);
    }
}
