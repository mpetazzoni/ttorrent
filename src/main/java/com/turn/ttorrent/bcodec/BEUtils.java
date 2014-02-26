/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.bcodec;

import com.turn.ttorrent.common.Torrent;
import javax.annotation.CheckForNull;

/**
 *
 * @author shevek
 */
public class BEUtils {

    public static String getString(@CheckForNull BEValue value)
            throws InvalidBEncodingException {
        if (value == null)
            return null;
        return value.getString(Torrent.BYTE_ENCODING);
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
}
