package com.turn.ttorrent.client.network;

import java.util.concurrent.BlockingQueue;

public interface WriteAttachment {

  /**
   * @return queue for offer/peek write tasks
   */
  BlockingQueue<WriteTask> getWriteTasks();

}
