package com.turn.ttorrent.client.network.keyProcessors;

import com.turn.ttorrent.client.network.ConnectionListener;
import com.turn.ttorrent.client.network.KeyAttachment;
import com.turn.ttorrent.common.MockTimeService;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.net.SocketTimeoutException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

import static org.mockito.Mockito.*;
import static org.testng.Assert.assertEquals;

@Test
public class CleanupKeyProcessorTest {

  private MockTimeService myTimeService;
  private KeyAttachment myKeyAttachment;
  private SelectionKey myKey;
  private SelectableChannel myChannel;
  private ConnectionListener myConnetionListener;

  @BeforeMethod
  public void setUp() throws Exception {
    myTimeService = new MockTimeService();
    myConnetionListener = mock(ConnectionListener.class);
    myKeyAttachment = new KeyAttachment(myConnetionListener, myTimeService);
    myKey = mock(SelectionKey.class);
    myChannel = SocketChannel.open();
    when(myKey.channel()).thenReturn(myChannel);
    myKey.attach(myKeyAttachment);
  }

  public void testSelected() {

    long oldTime = 10;
    myTimeService.setTime(oldTime);

    CleanupProcessor cleanupProcessor = new CleanupKeyProcessor(100);
    cleanupProcessor.processSelected(myKey);

    assertEquals(myKeyAttachment.getLastCommunicationTime(), oldTime);

    long newTime = 100;
    myTimeService.setTime(newTime);

    cleanupProcessor.processSelected(myKey);

    assertEquals(myKeyAttachment.getLastCommunicationTime(), newTime);
  }

  public void testCleanupWillCloseWithTimeout() throws Exception {
    long timeoutForClose = 20;
    myTimeService.setTime(10);

    myKeyAttachment.communicated();

    myTimeService.setTime(50);

    CleanupProcessor cleanupProcessor = new CleanupKeyProcessor(timeoutForClose);
    cleanupProcessor.processCleanup(myKey);

    verify(myKey).cancel();
    verify(myKey).channel();
    verifyNoMoreInteractions(myKey);

    verify(myConnetionListener).onError(any(SocketChannel.class), any(SocketTimeoutException.class));
  }

  public void testCleanupWithoutClose() {
    long timeoutForClose = 100;
    myTimeService.setTime(10);

    myKeyAttachment.communicated();

    myTimeService.setTime(50);

    CleanupProcessor cleanupProcessor = new CleanupKeyProcessor(timeoutForClose);
    cleanupProcessor.processCleanup(myKey);

    assertEquals(myKeyAttachment.getLastCommunicationTime(), 10);
    verify(myKey, never()).cancel();
  }

  @AfterMethod
  public void tearDown() throws Exception {
    myChannel.close();
  }
}