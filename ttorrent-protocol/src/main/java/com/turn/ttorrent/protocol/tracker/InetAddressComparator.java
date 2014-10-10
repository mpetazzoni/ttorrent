/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.protocol.tracker;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.Comparator;
import javax.annotation.Nonnull;

/**
 *
 * @author shevek
 */
public class InetAddressComparator implements Comparator<InetAddress> {

    public static final InetAddressComparator INSTANCE = new InetAddressComparator();

    private static int score(@Nonnull InetAddress a) {
        // Least likely to be a valid target address.
        if (a.isAnyLocalAddress())
            return 10;
        if (a.isMulticastAddress())
            return 6;
        if (a.isLoopbackAddress())
            return 4;
        if (a.isLinkLocalAddress())
            return 2;
        return 0;
        // Most likely to be a valid target address.
    }

    @Override
    public int compare(InetAddress o1, InetAddress o2) {
        int cmp;
        // Inet4Address is better than Inet6Address
        cmp = -Boolean.compare(o1 instanceof Inet4Address, o2 instanceof Inet4Address);
        if (cmp != 0)
            return cmp;
        // Avoid loopbacks.
        return Integer.compare(score(o1), score(o2));
    }
}
