/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.tracker;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import javax.annotation.Nonnull;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author shevek
 */
public class TrackerTest {

    private static final Logger LOG = LoggerFactory.getLogger(TrackerTest.class);

    private void test(@Nonnull Tracker tracker) throws Exception {
        LOG.info("Before start: " + tracker.getAnnounceUris());
        tracker.start();
        try {
            LOG.info("Running: " + tracker.getAnnounceUris());
        } finally {
            tracker.stop();
        }
        LOG.info("Done.");
    }

    @Test
    public void testListenAddresses() throws Exception {
        ADDR: {
            Tracker tracker = new Tracker();
            tracker.addListenAddress(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            test(tracker);
        }

        PORT: {
            Tracker tracker = new Tracker();
            tracker.addListenAddress(new InetSocketAddress(Tracker.DEFAULT_TRACKER_PORT));
            test(tracker);
        }

        INADDR_ANY: {
            Tracker tracker = new Tracker();
            tracker.addListenAddress(new InetSocketAddress(InetAddress.getLoopbackAddress(), Tracker.DEFAULT_TRACKER_PORT));
            test(tracker);
        }

        INADDR_4: {
            Tracker tracker = new Tracker();
            tracker.addListenAddress(new InetSocketAddress("0.0.0.0", Tracker.DEFAULT_TRACKER_PORT));
            test(tracker);
        }

    }
}