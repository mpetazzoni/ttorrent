/**
 * Copyright (C) 2011-2012 Turn, Inc.
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

import com.turn.ttorrent.client.peer.SharingPeer;

import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.EventListener;
import java.util.List;

/**
 * EventListener interface for objects that want to handle socket communications.
 *
 * @author mpetazzoni
 */
public interface CommunicationListener extends EventListener {

  public void handleNewConnection(SocketChannel s, String hexInfoHash);

  public void handleReturnedHandshake(SocketChannel s, List<ByteBuffer> data);

  public void handleNewData(SocketChannel s, List<ByteBuffer> data);

  public void handleFailedConnection(SharingPeer peer, Throwable cause);

  public void handleNewPeerConnection(SocketChannel s, byte[] peerId, String hexInfoHash);
}
