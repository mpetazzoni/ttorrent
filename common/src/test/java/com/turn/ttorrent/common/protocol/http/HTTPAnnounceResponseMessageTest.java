package com.turn.ttorrent.common.protocol.http;

import com.turn.ttorrent.bcodec.BEValue;
import com.turn.ttorrent.bcodec.BEncoder;
import com.turn.ttorrent.common.Peer;
import com.turn.ttorrent.common.protocol.TrackerMessage;
import org.testng.annotations.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.testng.Assert.assertEquals;

public class HTTPAnnounceResponseMessageTest {

  @Test
  public void parseTest() throws IOException, TrackerMessage.MessageValidationException {

    Map<String, BEValue> trackerResponse = new HashMap<String, BEValue>();
    trackerResponse.put("interval", new BEValue(5));
    trackerResponse.put("complete", new BEValue(1));
    trackerResponse.put("incomplete", new BEValue(0));

    String ip = "192.168.1.1";
    int port = 6881;
    InetSocketAddress peerAddress = new InetSocketAddress(ip, port);
    ByteBuffer binaryPeerAddress = ByteBuffer.allocate(6);
    binaryPeerAddress.put(peerAddress.getAddress().getAddress());
    binaryPeerAddress.putShort((short) port);
    trackerResponse.put("peers", new BEValue(binaryPeerAddress.array()));

    HTTPAnnounceResponseMessage parsedResponse = (HTTPAnnounceResponseMessage) HTTPAnnounceResponseMessage.parse(
            new ByteArrayInputStream(BEncoder.bencode(trackerResponse).array()));

    assertEquals(parsedResponse.getInterval(), 5);
    assertEquals(parsedResponse.getComplete(), 1);
    assertEquals(parsedResponse.getIncomplete(), 0);
    List<Peer> peers = parsedResponse.getPeers();
    assertEquals(peers.size(), 1);
    Peer peer = peers.get(0);
    assertEquals(peer.getIp(), ip);
    assertEquals(peer.getPort(), port);
  }
}
