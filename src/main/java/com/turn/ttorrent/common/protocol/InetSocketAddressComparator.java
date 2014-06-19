/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.common.protocol;

import java.net.InetSocketAddress;
import java.util.Comparator;

/**
 *
 * @author shevek
 */
public class InetSocketAddressComparator implements Comparator<InetSocketAddress> {

    public static final InetSocketAddressComparator INSTANCE = new InetSocketAddressComparator();

    @Override
    public int compare(InetSocketAddress o1, InetSocketAddress o2) {
        return InetAddressComparator.INSTANCE.compare(o1.getAddress(), o2.getAddress());
    }
}
