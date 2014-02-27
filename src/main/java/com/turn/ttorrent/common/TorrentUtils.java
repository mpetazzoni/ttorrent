/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.common;

import com.google.common.base.Function;
import com.google.common.collect.AbstractIterator;
import com.google.common.collect.Iterables;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
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

    @Nonnull
    public static Iterable<? extends InetAddress> getSpecificAddresses(@Nonnull InetAddress in) throws SocketException {
        if (!in.isAnyLocalAddress())
            return Collections.singleton(in);
        List<InetAddress> out = new ArrayList<InetAddress>();
        for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (iface.isLoopback())
                continue;
            if (!iface.isUp())
                continue;
            for (InetAddress ifaddr : Collections.list(iface.getInetAddresses())) {
                // LOG.info("ifaddr=" + ifaddr + " iftype=" + ifaddr.getClass() + " atype=" + addr.getClass());
                if (ifaddr.isLoopbackAddress())
                    continue;
                // If we prefer the IPv6 stack, then addr.getClass() is Inet6Address, but listens on IPv4 as well.
                // if (!ifaddr.getClass().equals(addr.getClass())) continue;
                out.add(ifaddr);
            }
        }
        return out;
    }

    @Nonnull
    public static Iterable<? extends InetSocketAddress> getSpecificAddresses(@Nonnull final InetSocketAddress in) throws SocketException {
        return Iterables.transform(getSpecificAddresses(in.getAddress()), new Function<InetAddress, InetSocketAddress>() {
            @Override
            public InetSocketAddress apply(InetAddress input) {
                return new InetSocketAddress(input, in.getPort());
            }
        });
    }
}