package com.turn.ttorrent.client.network.keyProcessors;

import com.turn.ttorrent.client.network.ConnectionListener;
import com.turn.ttorrent.client.network.KeyAttachment;
import com.turn.ttorrent.common.MockTimeService;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.net.SocketTimeoutException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

import static org.mockito.Mockito.*;
import static org.testng.Assert.assertEquals;

//@Test
public class CleanupKeyProcessorTest {

  private MockTimeService myTimeService;
  private KeyAttachment myKeyAttachment;
  private SelectionKey myKey;
  private SocketChannel myChannel;
  private ConnectionListener myConnetionListener;

  @BeforeMethod
  public void setUp() throws Exception {
    myTimeService = new MockTimeService();
    myConnetionListener = mock(ConnectionListener.class);
    myKeyAttachment = new KeyAttachment(myConnetionListener, myTimeService);
    myKey = mock(SelectionKey.class);
    myChannel = mock(SocketChannel.class);
    when(myKey.channel()).thenReturn(myChannel);
    myKey.attach(myKeyAttachment);
  }

  public void testSelected() {

    long oldTime = 10;
    myTimeService.setTime(oldTime);

    CleanupProcessor cleanupProcessor = new CleanupKeyProcessor();
    cleanupProcessor.processSelected(myKey);

    assertEquals(myKeyAttachment.getLastCommunicationTime(), oldTime);

    long newTime = 100;
    myTimeService.setTime(newTime);

    cleanupProcessor.processSelected(myKey);

    assertEquals(myKeyAttachment.getLastCommunicationTime(), newTime);
    verify(myKey, times(2)).attachment();
  }

  public void testCleanupWillCloseWithTimeout() throws Exception {
    long timeoutForDrop = 20;
    myTimeService.setTime(10);

    myKeyAttachment.communicated();

    myTimeService.setTime(50);

    CleanupProcessor cleanupProcessor = new CleanupKeyProcessor();
    cleanupProcessor.processCleanup(myKey);

    verify(myKey).attachment();
    verify(myKey).cancel();
    verify(myKey).channel();
    verify(myChannel).close();
    verifyNoMoreInteractions(myKey);

    verify(myConnetionListener).onError(eq(myChannel), any(SocketTimeoutException.class));
  }

  public void testCleanupWithoutDrop() {
    long timeoutForDrop = 100;
    myTimeService.setTime(10);

    myKeyAttachment.communicated();

    myTimeService.setTime(50);
    myKeyAttachment.communicated();

    myTimeService.setTime(500);

    CleanupProcessor cleanupProcessor = new CleanupKeyProcessor();
    cleanupProcessor.processCleanup(myKey);

    assertEquals(myKeyAttachment.getLastCommunicationTime(), 50);
    verify(myKey).attachment();
    verifyNoMoreInteractions(myKey);
  }
}