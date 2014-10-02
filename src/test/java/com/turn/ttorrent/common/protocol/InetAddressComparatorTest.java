/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.common.protocol;

import com.google.common.net.InetAddresses;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
// import static org.junit.Assert.*;

/**
 *
 * @author shevek
 */
public class InetAddressComparatorTest {

    private static final Logger LOG = LoggerFactory.getLogger(InetAddressComparatorTest.class);

    @Test
    public void testComparator() {
        List<InetAddress> addresses = new ArrayList<InetAddress>();
        addresses.add(InetAddresses.forString("1.2.3.4"));
        addresses.add(InetAddresses.forString("0.0.0.0"));
        addresses.add(InetAddresses.forString("::1"));
        addresses.add(InetAddresses.forString("fe80::a6"));
        Collections.shuffle(addresses);
        LOG.info("In: " + addresses);
        Collections.sort(addresses, new InetAddressComparator());
        LOG.info("Out: " + addresses);
    }
}