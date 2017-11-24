package com.turn.ttorrent.client.network.keyProcessors;

import com.turn.ttorrent.client.network.ConnectionListener;
import com.turn.ttorrent.client.network.KeyAttachment;
import com.turn.ttorrent.client.network.WriteTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

public class WritableKeyProcessor implements KeyProcessor {

  private static final Logger logger = LoggerFactory.getLogger(WritableKeyProcessor.class);
  private WriteTask processedTask = null;

  @Override
  public void process(SelectionKey key) throws IOException {
    SelectableChannel channel = key.channel();
    if (!(channel instanceof SocketChannel)) {
      logger.warn("incorrect instance of channel. The key is cancelled");
      key.cancel();
      return;
    }

    SocketChannel socketChannel = (SocketChannel) channel;

    if (mustGetNextTaskFromQueue()) {
      Object attachment = key.attachment();
      if (!(attachment instanceof KeyAttachment)) {
        logger.warn("incorrect instance of attachment for channel {}", new Object[]{socketChannel.socket()});
        socketChannel.close();
        return;
      }
      processedTask = ((KeyAttachment) attachment).getWriteTasks().poll();
      if (processedTask == null) {
        key.interestOps(SelectionKey.OP_READ);
        return;
      }
    }
    socketChannel.write(processedTask.getByteBuffer());
  }

  private boolean mustGetNextTaskFromQueue() {
    return processedTask == null || !processedTask.getByteBuffer().hasRemaining();
  }

  @Override
  public boolean accept(SelectionKey key) {
    return key.isWritable();
  }
}
