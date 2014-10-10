/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.protocol.tracker.http;

import com.google.common.collect.Multimap;
import com.turn.ttorrent.protocol.tracker.Peer;
import com.turn.ttorrent.protocol.tracker.TrackerMessage;
import com.turn.ttorrent.tracker.TrackerUtils;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import javax.annotation.Nonnull;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.junit.Assert.*;

/**
 *
 * @author shevek
 */
public class HTTPAnnounceRequestMessageTest {

    private static final Logger LOG = LoggerFactory.getLogger(HTTPAnnounceRequestMessageTest.class);

    private HTTPAnnounceRequestMessage test(@Nonnull HTTPAnnounceRequestMessage in) throws Exception {
        LOG.info("In: " + in);
        URI tracker = URI.create("http://localhost:3128/announce");
        URI request = in.toURI(tracker);
        LOG.info("Request: " + request);
        Multimap<String, String> params = TrackerUtils.parseQuery(request.getRawQuery());
        // for (Map.Entry<String, String> e : parser.entrySet()) LOG.info(e.getKey() + " -> " + e.getValue());
        HTTPAnnounceRequestMessage out = HTTPAnnounceRequestMessage.fromParams(params);
        LOG.info("Out: " + out);
        return out;
    }
    private Random random = new Random();

    @Nonnull
    private HTTPAnnounceRequestMessage newRequest(List<InetSocketAddress> addresses) {
        byte[] infoHash = new byte[20];
        random.nextBytes(infoHash);
        byte[] peerId = new byte[Peer.PEER_ID_LENGTH];
        random.nextBytes(peerId);
        return new HTTPAnnounceRequestMessage(infoHash, peerId, addresses,
                1, 2, 3, true, true, TrackerMessage.AnnounceEvent.NONE, 50);
    }

    private void test(@Nonnull InetSocketAddress... addresses) throws Exception {
        HTTPAnnounceRequestMessage message = test(newRequest(Arrays.asList(addresses)));
        assertEquals(Arrays.asList(addresses), message.getPeerAddresses());
    }

    @Test
    public void testRequest() throws Exception {
        test(new InetSocketAddress(123));
        test(new InetSocketAddress("1.2.3.4", 123));
        test(new InetSocketAddress("1.2.3.4", 123), new InetSocketAddress("2.3.4.5", 123));
        test(
                new InetSocketAddress("fe80::3e97:eff:fe67:5809", 6882),
                new InetSocketAddress("fe80::3e97:eff:fe67:5808", 6882),
                new InetSocketAddress("fe80::3e97:eff:fe67:5807", 6882)
                );
    }
}