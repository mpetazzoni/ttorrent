package com.turn.ttorrent.client.network;

import com.turn.ttorrent.TempFiles;
import com.turn.ttorrent.Utils;
import com.turn.ttorrent.client.*;
import com.turn.ttorrent.client.peer.PeerActivityListener;
import com.turn.ttorrent.client.peer.SharingPeer;
import com.turn.ttorrent.client.storage.FairPieceStorageFactory;
import com.turn.ttorrent.client.storage.FileCollectionStorage;
import com.turn.ttorrent.common.Peer;
import com.turn.ttorrent.common.TorrentCreator;
import com.turn.ttorrent.common.TorrentMetadata;
import com.turn.ttorrent.common.TorrentSerializer;
import com.turn.ttorrent.network.ConnectionManager;
import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.Pipe;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.testng.Assert.*;

@Test
public class HandshakeReceiverTest {

  private HandshakeReceiver myHandshakeReceiver;
  private byte[] mySelfId;
  private Context myContext;
  private TempFiles myTempFiles;

  public HandshakeReceiverTest() {
    if (Logger.getRootLogger().getAllAppenders().hasMoreElements())
      return;
    BasicConfigurator.configure(new ConsoleAppender(new PatternLayout("[%d{MMdd HH:mm:ss,SSS} %t] %6p - %20.20c - %m %n")));
  }

  @BeforeMethod
  public void setUp() throws Exception {
    myTempFiles = new TempFiles();
    Logger.getRootLogger().setLevel(Utils.getLogLevel());
    mySelfId = "selfId1selfId2selfId".getBytes();
    ByteBuffer selfId = ByteBuffer.wrap(mySelfId);
    myContext = mock(Context.class);
    PeersStorage peersStorage = new PeersStorage();
    TorrentsStorage torrentsStorage = new TorrentsStorage();
    when(myContext.getPeersStorage()).thenReturn(peersStorage);
    when(myContext.getTorrentsStorage()).thenReturn(torrentsStorage);
    peersStorage.setSelf(new Peer("127.0.0.1", 54645, selfId));
    CommunicationManager communicationManager = mock(CommunicationManager.class);
    when(communicationManager.getConnectionManager()).thenReturn(mock(ConnectionManager.class));
    myHandshakeReceiver = new HandshakeReceiver(
            myContext,
            "127.0.0.1",
            45664,
            false);
  }

  @AfterMethod
  public void tearDown() throws Exception {
    myTempFiles.cleanup();
  }

  public void testReceiveHandshake() throws Exception {
    Pipe p1 = Pipe.open();
    Pipe p2 = Pipe.open();
    ByteChannel client = new ByteSourceChannel(p1.source(), p2.sink());
    ByteChannel server = new ByteSourceChannel(p2.source(), p1.sink());
    String peerIdStr = "peerIdpeerIdpeerId22";
    String torrentHashStr = "torrenttorrenttorren";
    byte[] peerId = peerIdStr.getBytes();
    byte[] torrentHash = torrentHashStr.getBytes();
    Handshake hs = Handshake.craft(torrentHash, peerId);

    if (hs == null) {
      fail("Handshake instance is null");
    }

    ByteBuffer byteBuffer = hs.getData();
    client.write(byteBuffer);

    final File tempFile = myTempFiles.createTempFile(1024 * 1024);
    TorrentMetadata torrent = TorrentCreator.create(tempFile, URI.create(""), "test");
    File torrentFile = myTempFiles.createTempFile();
    FileOutputStream fos = new FileOutputStream(torrentFile);
    fos.write(new TorrentSerializer().serialize(torrent));
    fos.close();

    final LoadedTorrent loadedTorrent = mock(LoadedTorrent.class);
    final SharedTorrent sharedTorrent =
            SharedTorrent.fromFile(torrentFile,
                    FairPieceStorageFactory.INSTANCE.createStorage(torrent, FileCollectionStorage.create(torrent, tempFile.getParentFile())),
                    loadedTorrent.getTorrentStatistic());

    TorrentLoader torrentsLoader = mock(TorrentLoader.class);
    when(torrentsLoader.loadTorrent(loadedTorrent)).thenReturn(sharedTorrent);
    when(myContext.getTorrentLoader()).thenReturn(torrentsLoader);
    final ExecutorService executorService = Executors.newFixedThreadPool(1);
    when(myContext.getExecutor()).thenReturn(executorService);
    myContext.getTorrentsStorage().addTorrent(hs.getHexInfoHash(), loadedTorrent);

    final AtomicBoolean onConnectionEstablishedInvoker = new AtomicBoolean(false);

    final Semaphore semaphore = new Semaphore(0);
    when(myContext.createSharingPeer(any(String.class),
            anyInt(),
            any(ByteBuffer.class),
            any(SharedTorrent.class),
            any(ByteChannel.class),
            any(String.class),
            anyInt()))
            .thenReturn(new SharingPeer("127.0.0.1", 6881, ByteBuffer.wrap(peerId), sharedTorrent, null,
                    mock(PeerActivityListener.class), server, "TO", 1234) {
              @Override
              public void onConnectionEstablished() {
                onConnectionEstablishedInvoker.set(true);
                semaphore.release();
              }
            });

    PeersStorage peersStorage = myContext.getPeersStorage();
    assertEquals(0, myContext.getTorrentsStorage().activeTorrents().size());
    assertEquals(peersStorage.getSharingPeers().size(), 0);
    myHandshakeReceiver.processAndGetNext(server);
    assertEquals(peersStorage.getSharingPeers().size(), 1);
    ByteBuffer answer = ByteBuffer.allocate(byteBuffer.capacity());
    client.read(answer);
    answer.rewind();
    Handshake answerHs = Handshake.parse(answer);
    assertEquals(answerHs.getPeerId(), mySelfId);
    semaphore.tryAcquire(1, TimeUnit.SECONDS);
    assertTrue(onConnectionEstablishedInvoker.get());
    executorService.shutdown();
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
