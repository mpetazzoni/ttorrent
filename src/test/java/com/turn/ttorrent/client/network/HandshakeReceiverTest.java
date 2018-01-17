package com.turn.ttorrent.client.network;

import com.turn.ttorrent.Utils;
import com.turn.ttorrent.client.Client;
import com.turn.ttorrent.client.Handshake;
import com.turn.ttorrent.client.SharedTorrent;
import com.turn.ttorrent.client.peer.PeerActivityListener;
import com.turn.ttorrent.client.peer.SharingPeer;
import com.turn.ttorrent.common.*;
import org.apache.log4j.*;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.Pipe;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

@Test
public class HandshakeReceiverTest {

  private HandshakeReceiver myHandshakeReceiver;
  private PeersStorage myPeersStorage;
  private TorrentsStorage myTorrentsStorage;
  private byte[] mySelfId;
  private SharingPeerFactory mySharingPeerFactory;

  public HandshakeReceiverTest() {
    if (Logger.getRootLogger().getAllAppenders().hasMoreElements())
      return;
    BasicConfigurator.configure(new ConsoleAppender(new PatternLayout("[%d{MMdd HH:mm:ss,SSS} %t] %6p - %20.20c - %m %n")));
  }

  @BeforeMethod
  public void setUp() throws Exception {
    Logger.getRootLogger().setLevel(Utils.getLogLevel());
    PeersStorageProviderImpl peersStorageProviderImpl = new PeersStorageProviderImpl();
    TorrentsStorageProviderImpl torrentsStorageProviderImpl = new TorrentsStorageProviderImpl();
    myPeersStorage = peersStorageProviderImpl.getPeersStorage();
    mySelfId = "selfId1selfId2selfId".getBytes();
    ByteBuffer selfId = ByteBuffer.wrap(mySelfId);
    myPeersStorage.setSelf(new Peer("127.0.0.1", 54645, selfId));
    myTorrentsStorage = torrentsStorageProviderImpl.getTorrentsStorage();
    Client client = mock(Client.class);
    when(client.getConnectionManager()).thenReturn(mock(ConnectionManager.class));
    mySharingPeerFactory = mock(SharingPeerFactory.class);
    myHandshakeReceiver = new HandshakeReceiver(
            peersStorageProviderImpl,
            torrentsStorageProviderImpl,
            mock(ExecutorService.class),
            mySharingPeerFactory,
            "127.0.0.1",
            45664,
            false);
  }

  public void testReceiveHandshake() throws Exception {
    Pipe p1 = Pipe.open();
    Pipe p2 = Pipe.open();
    ByteChannel client = new ByteSourceChannel(p1.source(), p2.sink());
    ByteChannel server = new ByteSourceChannel(p2.source(), p1.sink());
    String peerIdStr = "peerIdpeerIdpeerId22";
    String torrentHashStr = "torrenttorrenttorren";
    String torrentHashHex = "746F7272656E74746F7272656E74746F7272656E";
    byte[] peerId = peerIdStr.getBytes();
    byte[] torrentHash = torrentHashStr.getBytes();
    Handshake hs = Handshake.craft(torrentHash, peerId);

    if (hs == null) {
      fail("Handshake instance is null");
    }

    ByteBuffer byteBuffer = hs.getData();
    client.write(byteBuffer);
    String torrentPath = "src" + File.separator + "test" + File.separator + "resources" + File.separator + "torrents" + File.separator + "file1.jar.torrent";
    final File torrent = new File(torrentPath);
    final SharedTorrent sharedTorrent = new SharedTorrent(Torrent.create(torrent, URI.create(""), ""), torrent.getParentFile(), false);
    myTorrentsStorage.put(torrentHashHex, sharedTorrent);

    final AtomicBoolean onConnectionEstablishedInvoker = new AtomicBoolean(false);

    when(mySharingPeerFactory.createSharingPeer(any(String.class),
            anyInt(),
            any(ByteBuffer.class),
            any(SharedTorrent.class),
            any(ByteChannel.class)))
            .thenReturn(new SharingPeer("127.0.0.1", 6881, ByteBuffer.wrap(peerId), sharedTorrent, null,
                    mock(PeerActivityListener.class), server) {
              @Override
              public void onConnectionEstablished() {
                onConnectionEstablishedInvoker.set(true);
              }
            });


    assertEquals(myPeersStorage.getSharingPeers().size(), 0);
    myHandshakeReceiver.processAndGetNext(server);
    assertEquals(myPeersStorage.getSharingPeers().size(), 1);
    ByteBuffer answer = ByteBuffer.allocate(byteBuffer.capacity());
    client.read(answer);
    answer.rewind();
    Handshake answerHs = Handshake.parse(answer);
    assertEquals(answerHs.getPeerId(), mySelfId);
    assertTrue(onConnectionEstablishedInvoker.get());
  }

  // TODO: 11/15/17 bad tests (e.g. incorrect torrentID, incorrect handshake, etc

  private static class ByteSourceChannel implements ByteChannel {

    private final Pipe.SourceChannel readChannel;
    private final Pipe.SinkChannel writeChannel;

    public ByteSourceChannel(Pipe.SourceChannel readChannel, Pipe.SinkChannel writeChannel) {
      this.readChannel = readChannel;
      this.writeChannel = writeChannel;
    }

    @Override
    public int read(ByteBuffer dst) throws IOException {
      return readChannel.read(dst);
    }

    @Override
    public int write(ByteBuffer src) throws IOException {
      return this.writeChannel.write(src);
    }

    @Override
    public boolean isOpen() {
      throw new RuntimeException("not implemented");
    }

    @Override
    public void close() throws IOException {
      readChannel.close();
      writeChannel.close();
    }
  }
}
