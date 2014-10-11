/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.tracker;

import com.google.common.base.Splitter;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.turn.ttorrent.protocol.bcodec.BEUtils;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.Map;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author shevek
 */
public class TrackerUtils {

    private static final Logger LOG = LoggerFactory.getLogger(TrackerUtils.class);
    /** Default server name and version announced by the tracker. */
    public static final String DEFAULT_VERSION_STRING = "BitTorrent Tracker (ttorrent)";
    /** Request path handled by the tracker announce request handler. */
    public static final String DEFAULT_ANNOUNCE_URL = "/announce";
    /** Default tracker listening port (BitTorrent's default is 6969). */
    public static final int DEFAULT_TRACKER_PORT = 6969;

    @Nonnull
    public static Multimap<String, String> parseQuery(String query) {
        Multimap<String, String> params = ArrayListMultimap.create();
        Splitter ampersand = Splitter.on('&').omitEmptyStrings();
        // Splitter equals = Splitter.on('=').limit(2);

        try {
            for (String pair : ampersand.split(query)) {
                String[] keyval = pair.split("[=]", 2);
                if (keyval.length == 1) {
                    parseParam(params, keyval[0], null);
                } else {
                    parseParam(params, keyval[0], keyval[1]);
                }
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            params.clear();
        }
        return params;
    }

    private static void parseParam(@Nonnull Multimap<String, String> params, @Nonnull String key, @CheckForNull String value) {
        try {
            if (value != null)
                value = URLDecoder.decode(value, BEUtils.BYTE_ENCODING_NAME);
            else
                value = "";
            params.put(key, value);
        } catch (UnsupportedEncodingException uee) {
            // Ignore, act like parameter was not there
            if (LOG.isDebugEnabled())
                LOG.debug("Could not decode {}", value);
        }
    }

    @Nonnull
    public static Multimap<String, String> parseQuery(Map<String, String[]> in) {
        Multimap<String, String> out = HashMultimap.create();
        for (Map.Entry<String, String[]> e : in.entrySet()) {
            for (String value : e.getValue())
                out.put(e.getKey(), value);
        }
        return out;
    }
}
