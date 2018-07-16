package com.turn.ttorrent.common.protocol.udp;

import com.turn.ttorrent.common.Peer;
import com.turn.ttorrent.common.protocol.TrackerMessage;
import org.testng.annotations.Test;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.List;

import static org.testng.Assert.assertEquals;

/**
 * Specification of UDP announce protocol:
 * <a href="http://www.bittorrent.org/beps/bep_0015.html">http://www.bittorrent.org/beps/bep_0015.html</a>
 */
public class UDPAnnounceResponseMessageTest {

  @Test
  public void parseTest() throws TrackerMessage.MessageValidationException {

    final int peersCount = 3;
    ByteBuffer response = ByteBuffer.allocate(20 + peersCount * 6);
    response.putInt(1);//announce response message identifier
    response.putInt(3);//transaction_id
    response.putInt(5);//interval
    response.putInt(1);//incomplete
    response.putInt(2);//complete


    String ipPrefix = "192.168.1.1";
    final int firstPort = 6881;
    for (int i = 0; i < peersCount; i++) {
      String ip = ipPrefix + i;
      InetSocketAddress peerAddress = new InetSocketAddress(ip, firstPort);
      response.put(peerAddress.getAddress().getAddress());
      response.putShort((short) (firstPort + i));
    }

    response.rewind();
    UDPAnnounceResponseMessage parsedResponse = UDPAnnounceResponseMessage.parse(response);
    assertEquals(parsedResponse.getActionId(), 1);
    assertEquals(parsedResponse.getTransactionId(), 3);
    assertEquals(parsedResponse.getInterval(), 5);
    assertEquals(parsedResponse.getComplete(), 2);
    assertEquals(parsedResponse.getIncomplete(), 1);
    List<Peer> peers = parsedResponse.getPeers();
    assertEquals(peers.size(), peersCount);
    for (int i = 0; i < peersCount; i++) {
      Peer peer = peers.get(i);
      assertEquals(peer.getIp(), ipPrefix + i);
      assertEquals(peer.getPort(), firstPort + i);
    }
  }
}
