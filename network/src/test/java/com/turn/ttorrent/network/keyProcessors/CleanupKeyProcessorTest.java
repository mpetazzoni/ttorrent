package com.turn.ttorrent.network.keyProcessors;

import com.turn.ttorrent.MockTimeService;
import com.turn.ttorrent.network.ConnectionListener;
import com.turn.ttorrent.network.TimeoutAttachment;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

import static org.mockito.Mockito.*;

@Test
public class CleanupKeyProcessorTest {

  private final int CLOSE_TIMEOUT = 100;

  private MockTimeService myTimeService;
  private TimeoutAttachment myTimeoutAttachment;
  private SelectionKey myKey;
  private SelectableChannel myChannel;
  private ConnectionListener myConnectionListener;

  @BeforeMethod
  public void setUp() throws Exception {
    myTimeService = new MockTimeService();
    myConnectionListener = mock(ConnectionListener.class);
    myTimeoutAttachment = mock(TimeoutAttachment.class);
    myKey = mock(SelectionKey.class);
    myChannel = SocketChannel.open();
    when(myKey.channel()).thenReturn(myChannel);
    when(myKey.interestOps()).thenReturn(SelectionKey.OP_READ);
    myKey.attach(myTimeoutAttachment);
  }

  public void testSelected() {

    long oldTime = 10;
    myTimeService.setTime(oldTime);

    CleanupProcessor cleanupProcessor = new CleanupKeyProcessor(myTimeService);
    cleanupProcessor.processSelected(myKey);

    verify(myTimeoutAttachment).communicatedNow(eq(oldTime));

    long newTime = 100;
    myTimeService.setTime(newTime);

    cleanupProcessor.processSelected(myKey);

    verify(myTimeoutAttachment).communicatedNow(eq(newTime));
  }

  public void testCleanupWillCloseWithTimeout() throws Exception {

    when(myTimeoutAttachment.isTimeoutElapsed(anyLong())).thenReturn(true);

    CleanupProcessor cleanupProcessor = new CleanupKeyProcessor(myTimeService);
    cleanupProcessor.processCleanup(myKey);

    verify(myKey).cancel();
    verify(myKey).channel();
    verify(myTimeoutAttachment).onTimeoutElapsed(any(SocketChannel.class));
    verifyNoMoreInteractions(myKey);
  }

  public void testCleanupWithoutClose() {
    when(myTimeoutAttachment.isTimeoutElapsed(anyLong())).thenReturn(false);

    myTimeService.setTime(200);

    CleanupProcessor cleanupProcessor = new CleanupKeyProcessor(myTimeService);
    cleanupProcessor.processCleanup(myKey);

    verify(myTimeoutAttachment).isTimeoutElapsed(myTimeService.now());
    verify(myKey, never()).cancel();
  }

  @AfterMethod
  public void tearDown() throws Exception {
    myChannel.close();
  }
}