package com.turn.ttorrent.client.network;

import com.turn.ttorrent.DummyPeerActivityListener;
import com.turn.ttorrent.client.Handshake;
import com.turn.ttorrent.client.SharedTorrent;
import com.turn.ttorrent.common.*;
import org.apache.log4j.*;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import sun.reflect.generics.reflectiveObjects.NotImplementedException;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.Pipe;

import static org.testng.Assert.assertEquals;

@Test
public class HandshakeReceiverTest {

  private HandshakeReceiver myHandshakeReceiver;
  private PeersStorage myPeersStorage;
  private TorrentsStorage myTorrentsStorage;
  private byte[] mySelfId;

  public HandshakeReceiverTest() {
    if (Logger.getRootLogger().getAllAppenders().hasMoreElements())
      return;
    BasicConfigurator.configure(new ConsoleAppender(new PatternLayout("[%d{MMdd HH:mm:ss,SSS} %t] %6p - %20.20c - %m %n")));
  }

  @BeforeMethod
  public void setUp() throws Exception {
    Logger.getRootLogger().setLevel(Level.INFO);
    PeersStorageProviderImpl peersStorageProviderImpl = new PeersStorageProviderImpl();
    TorrentsStorageProviderImpl torrentsStorageProviderImpl = new TorrentsStorageProviderImpl();
    myPeersStorage = peersStorageProviderImpl.getPeersStorage();
    mySelfId = "selfId1selfId2selfId".getBytes();
    ByteBuffer selfId = ByteBuffer.wrap(mySelfId);
    myPeersStorage.setSelf(new Peer("127.0.0.1", 54645, selfId));
    myTorrentsStorage = torrentsStorageProviderImpl.getTorrentsStorage();
    myHandshakeReceiver = new HandshakeReceiver(
            peersStorageProviderImpl,
            torrentsStorageProviderImpl,
            new DummyPeerActivityListener(),
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
    ByteBuffer byteBuffer = hs.getData();
    client.write(byteBuffer);
    String torrentPath = "src" + File.separator + "test" + File.separator + "resources" + File.separator + "torrents" + File.separator + "file1.jar.torrent";
    final File torrent = new File(torrentPath);
    myTorrentsStorage.put(torrentHashHex, new SharedTorrent(Torrent.create(torrent, URI.create(""), ""), torrent.getParentFile(), false));
    assertEquals(myPeersStorage.getSharingPeers().size(), 0);
    myHandshakeReceiver.processAndGetNext(server);
    assertEquals(myPeersStorage.getSharingPeers().size(), 1);
    ByteBuffer answer = ByteBuffer.allocate(byteBuffer.capacity());
    client.read(answer);
    answer.rewind();
    Handshake answerHs = Handshake.parse(answer);
    assertEquals(answerHs.getPeerId(), mySelfId);
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
      throw new NotImplementedException();
    }

    @Override
    public void close() throws IOException {
      readChannel.close();
      writeChannel.close();
    }
  }
}
