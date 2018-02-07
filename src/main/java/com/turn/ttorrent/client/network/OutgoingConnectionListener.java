package com.turn.ttorrent.client.network;

import com.turn.ttorrent.client.Context;
import com.turn.ttorrent.common.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ExecutorService;

public class OutgoingConnectionListener implements ConnectionListener {

  private volatile DataProcessor myNext;
  private final TorrentHash torrentHash;
  private final InetSocketAddress mySendAddress;
  private final Context myContext;

  public OutgoingConnectionListener(Context context,
                                    TorrentHash torrentHash,
                                    InetSocketAddress sendAddress) {
    this.torrentHash = torrentHash;
    this.mySendAddress = sendAddress;
    this.myNext = new ShutdownProcessor();
    myContext = context;
  }

  @Override
  public void onNewDataAvailable(SocketChannel socketChannel) throws IOException {
    this.myNext = this.myNext.processAndGetNext(socketChannel);
  }

  @Override
  public void onConnectionEstablished(SocketChannel socketChannel) throws IOException {
    HandshakeSender handshakeSender = new HandshakeSender(
            torrentHash,
            mySendAddress,
            myContext);
    this.myNext = handshakeSender.processAndGetNext(socketChannel);
  }

  @Override
  public void onError(SocketChannel socketChannel, Throwable ex) throws IOException {
    this.myNext.handleError(socketChannel, ex);
  }
}
