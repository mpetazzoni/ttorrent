package com.turn.ttorrent.client.network;

import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.testng.Assert.*;

public class ConnectionManagerTest {

  private ConnectionManager myConnectionManager;
  private ExecutorService myExecutorService;
  private ConnectionListener connectionListener;

  @BeforeMethod
  public void setUp() throws Exception {
    myExecutorService = Executors.newSingleThreadExecutor();
    ChannelListenerFactory channelListenerFactory = new ChannelListenerFactory() {
      @Override
      public ConnectionListener newChannelListener() {
        return connectionListener;
      }
    };
    myConnectionManager = new ConnectionManager(InetAddress.getByName("127.0.0.1"),
            channelListenerFactory,
            myExecutorService);
  }

  @Test
  public void canAcceptAndReadData() throws IOException, InterruptedException {
    final AtomicInteger acceptCount = new AtomicInteger();
    final AtomicInteger readCount = new AtomicInteger();
    final AtomicInteger connectCount = new AtomicInteger();
    final AtomicInteger lastReadBytesCount = new AtomicInteger();
    final ByteBuffer byteBuffer = ByteBuffer.allocate(10);

    final Semaphore semaphore = new Semaphore(0);

    this.connectionListener = new ConnectionListener() {
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
      public void onConnectionEstablished(SocketChannel socketChannel) throws IOException {
        acceptCount.incrementAndGet();
        semaphore.release();
      }

      @Override
      public void onError(SocketChannel socketChannel, Throwable ex) {

      }
    };

    myConnectionManager.initAndRunWorker();

    assertEquals(acceptCount.get(), 0);
    assertEquals(readCount.get(), 0);
    int serverPort = myConnectionManager.getBindAddress().getPort();

    Socket socket = new Socket("127.0.0.1", serverPort);

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
    myConnectionManager.offerConnect(new ConnectTask("127.0.0.1", otherPeerPort, new ConnectionListener() {
      @Override
      public void onNewDataAvailable(SocketChannel socketChannel) throws IOException {

      }

      @Override
      public void onConnectionEstablished(SocketChannel socketChannel) throws IOException {
        connectCount.incrementAndGet();
        semaphore.release();
      }

      @Override
      public void onError(SocketChannel socketChannel, Throwable ex) {

      }
    }), 1, TimeUnit.SECONDS);
    ss.accept();
    tryAcquireOrFail(semaphore);
    assertEquals(connectCount.get(), 1);

    this.myConnectionManager.close();
    myExecutorService.shutdown();
    assertTrue(myExecutorService.awaitTermination(10, TimeUnit.SECONDS));
  }

  private void tryAcquireOrFail(Semaphore semaphore) throws InterruptedException {
    if (!semaphore.tryAcquire(500, TimeUnit.MILLISECONDS)) {
      fail("don't get signal from connection receiver that connection selected");
    }
  }
}
