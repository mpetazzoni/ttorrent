package com.turn.ttorrent.network;

import java.io.IOException;
import java.nio.channels.SocketChannel;

public class AcceptAttachmentImpl implements AcceptAttachment, TimeoutAttachment {

  private final ChannelListenerFactory myChannelListenerFactory;

  public AcceptAttachmentImpl(ChannelListenerFactory channelListenerFactory) {
    this.myChannelListenerFactory = channelListenerFactory;
  }

  @Override
  public ChannelListenerFactory getChannelListenerFactory() {
    return myChannelListenerFactory;
  }

  @Override
  public boolean isTimeoutElapsed(long currentTimeMillis) {
    return false;//accept attachment doesn't closed by timeout
  }

  @Override
  public void communicatedNow(long currentTimeMillis) {
  }

  @Override
  public void onTimeoutElapsed(SocketChannel channel) throws IOException {

  }
}
