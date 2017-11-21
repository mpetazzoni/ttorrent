package com.turn.ttorrent.client.network;

import com.turn.ttorrent.common.PeersStorageProviderImpl;
import com.turn.ttorrent.common.TorrentHash;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.testng.Assert.*;

public class ConnectionManagerTest {

  private ConnectionManager myConnectionManager;
  private ChannelListener channelListener;

  @BeforeMethod
  public void setUp() throws Exception {
    ChannelListenerFactory channelListenerFactory = new ChannelListenerFactory() {
      @Override
      public ChannelListener newChannelListener() {
        return channelListener;
      }
    };
    myConnectionManager = new ConnectionManager(InetAddress.getByName("127.0.0.1"),
            new PeersStorageProviderImpl(),
            channelListenerFactory);
  }

  @Test
  public void canAcceptAndReadData() throws IOException, InterruptedException {
    final AtomicInteger acceptCount = new AtomicInteger();
    final AtomicInteger readCount = new AtomicInteger();
    final AtomicInteger connectCount = new AtomicInteger();
    final AtomicInteger lastReadBytesCount = new AtomicInteger();
    final ByteBuffer byteBuffer = ByteBuffer.allocate(10);

    final Semaphore semaphore = new Semaphore(0);

    this.channelListener = new ChannelListener() {
      @Override
      public void onNewDataAvailable(SocketChannel socketChannel) throws IOException {
        readCount.incrementAndGet();
        lastReadBytesCount.set(socketChannel.read(byteBuffer));
        if (lastReadBytesCount.get() == -1) {
          socketChannel.close();
        }
        semaphore.release();
      }

      @Override
      public void onConnectionAccept(SocketChannel socketChannel) throws IOException {
        acceptCount.incrementAndGet();
        semaphore.release();
      }

      @Override
      public void onConnected(SocketChannel socketChannel, ConnectTask connectTask) {
        connectCount.incrementAndGet();
        semaphore.release();
      }
    };

    ExecutorService executorService = Executors.newSingleThreadExecutor();
    Future<?> future = executorService.submit(myConnectionManager);

    assertEquals(acceptCount.get(), 0);
    assertEquals(readCount.get(), 0);
    int serverPort = ConnectionManager.PORT_RANGE_START;
    Socket socket = null;
    while (serverPort < ConnectionManager.PORT_RANGE_END) {
      try {
        socket = new Socket("127.0.0.1", serverPort);
        if (socket.isConnected()) break;
      } catch (ConnectException ignored) {}
      serverPort++;
    }

    if (socket == null || !socket.isConnected()) {
      fail("can not connect to server channel of connection manager");
    }

    tryAcquireOrFail(semaphore);//wait until connection is accepted

    assertTrue(socket.isConnected());
    assertEquals(acceptCount.get(), 1);
    assertEquals(readCount.get(), 0);

    Socket socketSecond = new Socket("127.0.0.1", serverPort);

    tryAcquireOrFail(semaphore);//wait until connection is accepted

    assertTrue(socketSecond.isConnected());
    assertEquals(acceptCount.get(), 2);
    assertEquals(readCount.get(), 0);
    socketSecond.close();
    tryAcquireOrFail(semaphore);//wait read that connection is closed
    assertEquals(readCount.get(), 1);
    assertEquals(acceptCount.get(), 2);
    assertEquals(lastReadBytesCount.get(), -1);
    byteBuffer.rewind();
    assertEquals(byteBuffer.get(), 0);
    byteBuffer.rewind();
    String writeStr = "abc";
    OutputStream outputStream = socket.getOutputStream();
    outputStream.write(writeStr.getBytes());
    tryAcquireOrFail(semaphore);//wait until read bytes
    assertEquals(readCount.get(), 2);
    assertEquals(lastReadBytesCount.get(), 3);
    byte[] expected = new byte[byteBuffer.capacity()];
    System.arraycopy(writeStr.getBytes(), 0, expected, 0, writeStr.length());
    assertEquals(byteBuffer.array(), expected);
    outputStream.close();
    socket.close();
    tryAcquireOrFail(semaphore);//wait read that connection is closed
    assertEquals(readCount.get(), 3);

    int otherPeerPort = 7575;
    ServerSocket ss = new ServerSocket(otherPeerPort);
    assertEquals(connectCount.get(), 0);
    myConnectionManager.connect(new ConnectTask("127.0.0.1", otherPeerPort, new TorrentHash() {
      @Override
      public byte[] getInfoHash() {
        return new byte[0];
      }

      @Override
      public String getHexInfoHash() {
        return null;
      }
    }), 1, TimeUnit.SECONDS);
    ss.accept();
    tryAcquireOrFail(semaphore);
    assertEquals(connectCount.get(), 1);

    executorService.shutdownNow();
    boolean executorShutdownCorrectly = executorService.awaitTermination(10, TimeUnit.SECONDS);
    assertTrue(executorShutdownCorrectly);
    executorService.shutdown();
    executorService.awaitTermination(1, TimeUnit.MINUTES);
  }

  private void tryAcquireOrFail(Semaphore semaphore) throws InterruptedException {
    if (!semaphore.tryAcquire(500, TimeUnit.MILLISECONDS)) {
      fail("don't get signal from connection receiver that connection selected");
    }
  }
}
