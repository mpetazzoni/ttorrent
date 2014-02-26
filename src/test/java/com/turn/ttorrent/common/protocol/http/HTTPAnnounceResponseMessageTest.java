/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.common.protocol.http;

import com.turn.ttorrent.bcodec.BEValue;
import com.turn.ttorrent.common.Peer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import javax.annotation.Nonnull;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author shevek
 */
public class HTTPAnnounceResponseMessageTest {

    private static final Logger LOG = LoggerFactory.getLogger(HTTPAnnounceResponseMessageTest.class);

    private void test(HTTPAnnounceResponseMessage in, boolean compact, boolean noPeerIds) throws Exception {
        LOG.info("in=" + in + ", compact=" + compact + ", noPeerIds=" + noPeerIds);
        Map<String, BEValue> value = in.toBEValue(compact, noPeerIds);
        HTTPAnnounceResponseMessage out = HTTPAnnounceResponseMessage.fromBEValue(value);
        LOG.info("out=" + out);
    }

    private void test(HTTPAnnounceResponseMessage message) throws Exception {
        test(message, true, true);
        test(message, true, false);
        test(message, false, true);
        test(message, false, false);
    }
    private Random random = new Random();

    @Nonnull
    private Peer newPeer(int len, boolean hasPeerId) throws UnknownHostException {
        byte[] address = new byte[len];
        random.nextBytes(address);
        int port = random.nextInt() & 0xFFFF;
        byte[] peerId = null;
        if (hasPeerId) {
            peerId = new byte[Peer.PEER_ID_LENGTH];
            random.nextBytes(peerId);
        }
        InetAddress inaddr = InetAddress.getByAddress(address);
        return new Peer(new InetSocketAddress(inaddr, port), peerId);
    }

    @Test
    public void testSerialization() throws Exception {
        InetAddress clientAddress = InetAddress.getLoopbackAddress();

        EMPTY:
        {
            List<Peer> peers = new ArrayList<Peer>();
            test(new HTTPAnnounceResponseMessage(
                    clientAddress,
                    1, 2, 3,
                    peers));
        }

        IP4_WITHOUT:
        {
            List<Peer> peers = new ArrayList<Peer>();
            peers.add(newPeer(4, false));
            test(new HTTPAnnounceResponseMessage(
                    clientAddress,
                    1, 2, 3,
                    peers));
        }

        IP4_WITH:
        {
            List<Peer> peers = new ArrayList<Peer>();
            peers.add(newPeer(4, true));
            test(new HTTPAnnounceResponseMessage(
                    clientAddress,
                    1, 2, 3,
                    peers));
        }

        IP6_WITHOUT:
        {
            List<Peer> peers = new ArrayList<Peer>();
            peers.add(newPeer(16, false));
            test(new HTTPAnnounceResponseMessage(
                    clientAddress,
                    1, 2, 3,
                    peers));
        }

        IP6_WITH:
        {
            List<Peer> peers = new ArrayList<Peer>();
            peers.add(newPeer(16, true));
            test(new HTTPAnnounceResponseMessage(
                    clientAddress,
                    1, 2, 3,
                    peers));
        }

        ALL:
        {
            List<Peer> peers = new ArrayList<Peer>();
            for (int i = 0; i < 3; i++) {
                peers.add(newPeer(4, false));
                peers.add(newPeer(4, true));
                peers.add(newPeer(16, false));
                peers.add(newPeer(16, true));
            }
            test(new HTTPAnnounceResponseMessage(
                    clientAddress,
                    1, 2, 3,
                    peers));
        }

    }
}