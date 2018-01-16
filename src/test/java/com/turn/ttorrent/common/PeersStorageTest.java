package com.turn.ttorrent.common;

import com.turn.ttorrent.client.SharedTorrent;
import com.turn.ttorrent.client.peer.PeerActivityListener;
import com.turn.ttorrent.client.peer.SharingPeer;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
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
    SharingPeer sharingPeer = getMockSharingPeer(null);
    myPeersStorage.putIfAbsent(new PeerUID("", ""), sharingPeer);
    Collection<SharingPeer> sharingPeers = myPeersStorage.getSharingPeers();

    assertEquals(1, myPeersStorage.getSharingPeers().size());
    assertEquals(1, sharingPeers.size());

    sharingPeers.add(sharingPeer);

    assertEquals(1, myPeersStorage.getSharingPeers().size());
    assertEquals(2, sharingPeers.size());
  }

  private SharingPeer getMockSharingPeer(ByteBuffer id) {
    return new SharingPeer("1", 1, id, mock(SharedTorrent.class), null, mock(PeerActivityListener.class));
  }

  public void getAndRemoveSharingPeersTest() {
    SharingPeer sharingPeer = getMockSharingPeer(null);
    PeerUID peerUid = new PeerUID("", "");
    SharingPeer oldPeer = myPeersStorage.putIfAbsent(peerUid, sharingPeer);

    assertNull(oldPeer);
    assertEquals(myPeersStorage.getSharingPeer(peerUid), sharingPeer);

    assertEquals(myPeersStorage.removeSharingPeer(peerUid), sharingPeer);
    assertNull(myPeersStorage.removeSharingPeer(peerUid));
  }

  public void mappingIdTest() throws UnsupportedEncodingException {
    byte[] peerId = {1, 2, 3};
    String peerIdString = new String(peerId, Torrent.BYTE_ENCODING);
    SharingPeer sharingPeer = getMockSharingPeer(ByteBuffer.wrap(peerId));
    PeerUID peerUid = new PeerUID(peerIdString, "");
    myPeersStorage.putIfAbsent(peerUid, sharingPeer, false);

    assertNull(myPeersStorage.getPeerIdByAddress("1", 1));

    myPeersStorage.putIfAbsent(peerUid, sharingPeer, true);

    assertNull(myPeersStorage.getPeerIdByAddress("1", 1));

    myPeersStorage.removeSharingPeer(peerUid);
    myPeersStorage.putIfAbsent(peerUid, sharingPeer, true);

    assertEquals(myPeersStorage.getPeerIdByAddress("1", 1), peerIdString);

    assertNull(myPeersStorage.getPeerIdByAddress("2", 1));
    assertNull(myPeersStorage.getPeerIdByAddress("1", 2));

  }
}
