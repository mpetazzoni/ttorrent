package com.turn.ttorrent.network.keyProcessors;

import com.turn.ttorrent.common.LoggerUtils;
import com.turn.ttorrent.common.TimeService;
import com.turn.ttorrent.common.TorrentLoggerFactory;
import com.turn.ttorrent.network.TimeoutAttachment;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

public class CleanupKeyProcessor implements CleanupProcessor {

  private final static Logger logger = TorrentLoggerFactory.getLogger();

  private final TimeService myTimeService;

  public CleanupKeyProcessor(TimeService timeService) {
    this.myTimeService = timeService;
  }

  @Override
  public void processCleanup(SelectionKey key) {
    TimeoutAttachment attachment = KeyProcessorUtil.getAttachmentAsTimeoutOrNull(key);
    if (attachment == null) {
      key.cancel();
      return;
    }
    if (attachment.isTimeoutElapsed(myTimeService.now())) {

      SocketChannel channel = KeyProcessorUtil.getCastedChannelOrNull(key);
      if (channel == null) {
        key.cancel();
        return;
      }

      logger.debug("channel {} was inactive in specified timeout. Close channel...", channel);
      try {
        channel.close();
        key.cancel();
        attachment.onTimeoutElapsed(channel);
      } catch (IOException e) {
        LoggerUtils.errorAndDebugDetails(logger, "unable close channel {}", channel, e);
      }
    }
  }

  @Override
  public void processSelected(SelectionKey key) {
    TimeoutAttachment attachment = KeyProcessorUtil.getAttachmentAsTimeoutOrNull(key);
    if (attachment == null) {
      key.cancel();
      return;
    }
    attachment.communicatedNow(myTimeService.now());
  }
}
