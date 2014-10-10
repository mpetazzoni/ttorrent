/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.protocol.bcodec;

import com.google.common.base.Charsets;
import java.nio.charset.Charset;
import javax.annotation.CheckForNull;

/**
 *
 * @author shevek
 */
public class BEUtils {

    /** The query parameters encoding when parsing byte strings. */
    public static final Charset BYTE_ENCODING = Charsets.ISO_8859_1;
    public static final String BYTE_ENCODING_NAME = BYTE_ENCODING.name();

    @CheckForNull
    public static String getString(@CheckForNull BEValue value)
            throws InvalidBEncodingException {
        if (value == null)
            return null;
        return value.getString(BYTE_ENCODING);
    }

    @CheckForNull
    public static byte[] getBytes(@CheckForNull BEValue value)
            throws InvalidBEncodingException {
        if (value == null)
            return null;
        return value.getBytes();
    }

    public static int getInt(@CheckForNull BEValue value, int dflt)
            throws InvalidBEncodingException {
        if (value == null)
            return dflt;
        return value.getInt();
    }

    private BEUtils() {
    }
}
