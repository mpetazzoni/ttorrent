package com.turn.ttorrent.client.network;

import com.turn.ttorrent.client.network.keyProcessors.CleanupProcessor;
import com.turn.ttorrent.client.network.keyProcessors.KeyProcessor;
import com.turn.ttorrent.common.MockTimeService;
import org.testng.annotations.Test;

import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@Test
public class ConnectionWorkerTest {

  public void testCleanupIsCalled() throws Exception {

    final SelectionKey mockKey = mock(SelectionKey.class);
    final KeyProcessor acceptProcessor = mock(KeyProcessor.class);
    final KeyProcessor notAcceptProcessor = mock(KeyProcessor.class);

    Selector mockSelector = mock(Selector.class);
    when(mockSelector.select(anyLong())).thenReturn(1).thenThrow(new ClosedSelectorException());
    when(mockSelector.selectedKeys()).thenReturn(new HashSet<SelectionKey>(Collections.singleton(mockKey)));
    when(mockKey.isValid()).thenReturn(true);
    when(acceptProcessor.accept(mockKey)).thenReturn(true);
    when(notAcceptProcessor.accept(mockKey)).thenReturn(false);
    ConnectionWorker connectionWorker = new ConnectionWorker(
            mockSelector,
            Arrays.asList(acceptProcessor, notAcceptProcessor),
            10,
            0,
            new MockTimeService(),
            mock(CleanupProcessor.class),
            mock(NewConnectionAllower.class));
    connectionWorker.run();
    verify(mockSelector).selectedKeys();
    verify(acceptProcessor).accept(mockKey);
    verify(acceptProcessor).process(mockKey);
    verify(notAcceptProcessor).accept(mockKey);
    verifyNoMoreInteractions(notAcceptProcessor);
  }
}

