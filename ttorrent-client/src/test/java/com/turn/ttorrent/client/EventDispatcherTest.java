/*
 * Copyright 2000-2018 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.turn.ttorrent.client;

import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.Mockito.mock;
import static org.testng.Assert.assertEquals;

@Test
public class EventDispatcherTest {

  private EventDispatcher eventDispatcher;
  private PeerInformation peerInfo;
  private PieceInformation pieceInfo;

  @BeforeMethod
  public void setUp() {
    eventDispatcher = new EventDispatcher();

    peerInfo = mock(PeerInformation.class);
    pieceInfo = mock(PieceInformation.class);
  }

  public void testWithoutListeners() {

    eventDispatcher.multicaster().downloadFailed(new RuntimeException());
    eventDispatcher.multicaster().peerConnected(peerInfo);
    eventDispatcher.multicaster().validationComplete(1, 4);
    eventDispatcher.multicaster().pieceDownloaded(pieceInfo, peerInfo);
    eventDispatcher.multicaster().downloadComplete();
    eventDispatcher.multicaster().pieceReceived(pieceInfo, peerInfo);
    eventDispatcher.multicaster().peerDisconnected(peerInfo);
  }

  public void testInvocation() {

    final AtomicInteger invocationCount = new AtomicInteger();
    int count = 5;
    for (int i = 0; i < count; i++) {
      eventDispatcher.addListener(new TorrentListenerWrapper() {
        @Override
        public void downloadComplete() {
          invocationCount.incrementAndGet();
        }
      });
    }

    eventDispatcher.multicaster().peerConnected(peerInfo);

    assertEquals(invocationCount.get(), 0);

    eventDispatcher.multicaster().downloadComplete();

    assertEquals(invocationCount.get(), count);

  }
}
