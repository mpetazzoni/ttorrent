package com.turn.ttorrent.network;

import java.util.concurrent.BlockingQueue;

public interface WriteAttachment {

  /**
   * @return queue for offer/peek write tasks
   */
  BlockingQueue<WriteTask> getWriteTasks();

}
