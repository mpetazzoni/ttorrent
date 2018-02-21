package com.turn.ttorrent.client.network;

import com.turn.ttorrent.client.Context;
import com.turn.ttorrent.network.ConnectionListener;

import java.io.IOException;
import java.nio.channels.SocketChannel;

public class StateChannelListener implements ConnectionListener {

  private volatile DataProcessor myNext;
  private final Context myContext;

  public StateChannelListener(Context context) {
    myContext = context;
    myNext = new ShutdownProcessor();
  }

  @Override
  public void onNewDataAvailable(SocketChannel socketChannel) throws IOException {
    this.myNext = this.myNext.processAndGetNext(socketChannel);
  }

  @Override
  public void onConnectionEstablished(SocketChannel socketChannel) throws IOException {
    this.myNext = new HandshakeReceiver(
            myContext,
            socketChannel.socket().getInetAddress().getHostAddress(),
            socketChannel.socket().getPort(),
            false);
  }

  @Override
  public void onError(SocketChannel socketChannel, Throwable ex) throws IOException {
    this.myNext = this.myNext.handleError(socketChannel, ex);
  }
}
