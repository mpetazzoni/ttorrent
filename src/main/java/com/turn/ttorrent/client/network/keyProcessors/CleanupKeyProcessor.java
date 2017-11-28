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
    KeyAttachment attachment = KeyProcessorUtil.getCastedAttachmentOrNull(key, logger);
    if (attachment == null) {
      key.cancel();
      return;
    }
    SocketChannel channel = KeyProcessorUtil.getCastedChannelOrNull(key, logger);
    if (channel == null) {
      key.cancel();
      return;
    }
    if (attachment.isTimeoutElapsed(timeoutMillis)) {
      try {
        channel.close();
        key.cancel();
        attachment.getConnectionListener().onError(channel, new SocketTimeoutException());
      } catch (IOException e) {
        LoggerUtils.errorAndDebugDetails(logger, "unable close channel {}", channel, e);
      }
    }
  }

  @Override
  public void processSelected(SelectionKey key) {
    KeyAttachment attachment = KeyProcessorUtil.getCastedAttachmentOrNull(key, logger);
    if (attachment == null) {
      key.cancel();
      return;
    }
    attachment.communicated();
  }
}
