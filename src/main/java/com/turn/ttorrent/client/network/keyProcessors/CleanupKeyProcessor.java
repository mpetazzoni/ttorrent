package com.turn.ttorrent.client.network.keyProcessors;

import com.turn.ttorrent.client.network.KeyAttachment;
import com.turn.ttorrent.common.LoggerUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

public class CleanupKeyProcessor implements CleanupProcessor {

  private final static Logger logger = LoggerFactory.getLogger(CleanupKeyProcessor.class);

  private final long timeoutMillis;

  public CleanupKeyProcessor(long timeoutMillis) {
    this.timeoutMillis = timeoutMillis;
  }

  @Override
  public void processCleanup(SelectionKey key) {
    boolean isConnectOrAcceptKey = isConnectOrAcceptKey(key);
    if (isConnectOrAcceptKey) return;
    KeyAttachment attachment = KeyProcessorUtil.getCastedAttachmentOrNull(key);
    if (attachment == null) {
      key.cancel();
      return;
    }
    SocketChannel channel = KeyProcessorUtil.getCastedChannelOrNull(key);
    if (channel == null) {
      key.cancel();
      return;
    }
    if (attachment.isTimeoutElapsed(timeoutMillis)) {
      logger.trace("channel {} was inactive in specified timeout {}ms. Close channel...", channel, timeoutMillis);
      try {
        channel.close();
        key.cancel();
        attachment.getConnectionListener().onError(channel, new SocketTimeoutException());
      } catch (IOException e) {
        LoggerUtils.errorAndDebugDetails(logger, "unable close channel {}", channel, e);
      }
    }
  }

  private boolean isConnectOrAcceptKey(SelectionKey key) {
    int interestOps = key.interestOps();
    boolean isConnect = (interestOps & SelectionKey.OP_ACCEPT) != 0;
    boolean isAccept = (interestOps & SelectionKey.OP_CONNECT) != 0;
    return isConnect || isAccept;
  }

  @Override
  public void processSelected(SelectionKey key) {
    boolean isConnectOrAcceptKey = isConnectOrAcceptKey(key);
    if (isConnectOrAcceptKey) return;
    KeyAttachment attachment = KeyProcessorUtil.getCastedAttachmentOrNull(key);
    if (attachment == null) {
      key.cancel();
      return;
    }
    attachment.communicated();
  }
}
