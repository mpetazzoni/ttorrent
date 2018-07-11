package com.turn.ttorrent.client;

import com.turn.ttorrent.client.peer.PeerActivityListener;
import com.turn.ttorrent.client.peer.SharingPeer;
import com.turn.ttorrent.common.Peer;
import com.turn.ttorrent.common.PeerUID;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.net.InetSocketAddress;
import java.nio.channels.ByteChannel;
import java.util.Collection;

import static org.mockito.Mockito.mock;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;

@Test
public class PeersStorageTest {

  private PeersStorage myPeersStorage;

  @BeforeMethod
  public void setUp() throws Exception {
    myPeersStorage = new PeersStorage();
  }

  public void getSetSelfTest() {

    assertNull(myPeersStorage.getSelf());
    Peer self = new Peer("", 1);
    myPeersStorage.setSelf(self);
    assertEquals(myPeersStorage.getSelf(), self);
  }

  public void testThatPeersStorageReturnNewCollection() {
    SharingPeer sharingPeer = getMockSharingPeer();
    myPeersStorage.putIfAbsent(new PeerUID(new InetSocketAddress("127.0.0.1", 6881), ""), sharingPeer);
    Collection<SharingPeer> sharingPeers = myPeersStorage.getSharingPeers();

    assertEquals(1, myPeersStorage.getSharingPeers().size());
    assertEquals(1, sharingPeers.size());

    sharingPeers.add(sharingPeer);

    assertEquals(1, myPeersStorage.getSharingPeers().size());
    assertEquals(2, sharingPeers.size());
  }

  private SharingPeer getMockSharingPeer() {
    return new SharingPeer("1",
            1,
            null,
            mock(SharedTorrent.class),
            null,
            mock(PeerActivityListener.class),
            mock(ByteChannel.class), "TO", 1234);
  }

  public void getAndRemoveSharingPeersTest() {
    SharingPeer sharingPeer = getMockSharingPeer();
    PeerUID peerUid = new PeerUID(new InetSocketAddress("127.0.0.1", 6881), "");
    SharingPeer oldPeer = myPeersStorage.putIfAbsent(peerUid, sharingPeer);

    assertNull(oldPeer);
    assertEquals(myPeersStorage.getSharingPeer(peerUid), sharingPeer);

    assertEquals(myPeersStorage.removeSharingPeer(peerUid), sharingPeer);
    assertNull(myPeersStorage.removeSharingPeer(peerUid));
  }
}
