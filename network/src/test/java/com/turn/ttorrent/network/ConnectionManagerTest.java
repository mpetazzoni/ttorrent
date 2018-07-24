package com.turn.ttorrent.network;

import com.turn.ttorrent.MockTimeService;
import org.apache.log4j.*;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.testng.Assert.*;

public class ConnectionManagerTest {

  private ConnectionManager myConnectionManager;
  private ExecutorService myExecutorService;
  private ConnectionListener connectionListener;
  private ConnectionManagerContext myContext;

  public ConnectionManagerTest() {
    if (Logger.getRootLogger().getAllAppenders().hasMoreElements())
      return;
    BasicConfigurator.configure(new ConsoleAppender(new PatternLayout("[%d{MMdd HH:mm:ss,SSS} %t] %6p - %20.20c - %m %n")));
    Logger.getRootLogger().setLevel(Level.ALL);
  }

  @BeforeMethod
  public void setUp() throws Exception {
    Logger.getRootLogger().setLevel(Level.INFO);
    myContext = mock(ConnectionManagerContext.class);
    myExecutorService = Executors.newSingleThreadExecutor();
    when(myContext.getExecutor()).thenReturn(myExecutorService);
    final SelectorFactory selectorFactory = mock(SelectorFactory.class);
    when(selectorFactory.newSelector()).thenReturn(Selector.open());
    NewConnectionAllower newConnectionAllower = mock(NewConnectionAllower.class);
    when(newConnectionAllower.isNewConnectionAllowed()).thenReturn(true);
    myConnectionManager = new ConnectionManager(
            myContext,
            new MockTimeService(),
            newConnectionAllower,
            newConnectionAllower,
            selectorFactory,
            new AtomicInteger(),
            new AtomicInteger());
  }

  @Test(expectedExceptions = IllegalStateException.class)
  public void testThatDoubleInitThrowException() {
    try {
      myConnectionManager.initAndRunWorker(new FirstAvailableChannel(6881, 6889));
    } catch (IOException e) {
      fail("unable to init and run worker", e);
    }
    try {
      myConnectionManager.initAndRunWorker(new FirstAvailableChannel(6881, 6889));
    } catch (IOException e) {
      fail("unable to init and run worker", e);
    }
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

    when(myContext.newChannelListener()).thenReturn(connectionListener);

    myConnectionManager.initAndRunWorker(new FirstAvailableChannel(6881, 6889));

    assertEquals(acceptCount.get(), 0);
    assertEquals(readCount.get(), 0);
    int serverPort = myConnectionManager.getBindPort();

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
    }, 0, 100), 1, TimeUnit.SECONDS);
    ss.accept();
    tryAcquireOrFail(semaphore);
    assertEquals(connectCount.get(), 1);
  }

  @AfterMethod
  public void tearDown() throws Exception {
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
