package com.turn.ttorrent.client.network;

import com.turn.ttorrent.client.network.keyProcessors.CleanupProcessor;
import com.turn.ttorrent.client.network.keyProcessors.KeyProcessor;
import com.turn.ttorrent.common.LoggerUtils;
import com.turn.ttorrent.common.TimeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class ConnectionWorker implements Runnable {

  private static final Logger logger = LoggerFactory.getLogger(ConnectionWorker.class);
  private volatile boolean stop = false;
  private final Selector selector;
  private final BlockingQueue<ConnectTask> myConnectQueue;
  private final BlockingQueue<WriteTask> myWriteQueue;
  private final CountDownLatch myCountDownLatch;
  private final List<KeyProcessor> myKeyProcessors;
  private final TimeService myTimeService;
  private long lastCleanupTime;
  private final int mySelectorTimeoutMillis;
  private final int myCleanupTimeoutMillis;
  private final CleanupProcessor myCleanupProcessor;

  public ConnectionWorker(Selector selector,
                          List<KeyProcessor> keyProcessors,
                          int selectorTimeoutMillis,
                          int cleanupTimeoutMillis,
                          TimeService timeService,
                          CleanupProcessor cleanupProcessor) {
    this.selector = selector;
    this.myTimeService = timeService;
    this.lastCleanupTime = timeService.now();
    this.mySelectorTimeoutMillis = selectorTimeoutMillis;
    this.myCleanupTimeoutMillis = cleanupTimeoutMillis;
    this.myCleanupProcessor = cleanupProcessor;
    this.myCountDownLatch = new CountDownLatch(1);
    this.myConnectQueue = new LinkedBlockingQueue<ConnectTask>(100);
    this.myKeyProcessors = keyProcessors;
    this.myWriteQueue = new LinkedBlockingQueue<WriteTask>(100);
  }

  public CountDownLatch getCountDownLatch() {
    return myCountDownLatch;
  }

  @Override
  public void run() {

    try {
      while (!stop && (!Thread.currentThread().isInterrupted())) {
        try {
          logger.trace("try select keys from selector");
          int selected;
          try {
            selected = selector.select(mySelectorTimeoutMillis);
          } catch (ClosedSelectorException e) {
            break;
          }
          connectToPeersFromQueue();
          processWriteTasks();
          logger.trace("select keys from selector. Keys count is " + selected);
          if (selected == 0) {
            continue;
          }
          processSelectedKeys();
          if (needRunCleanup()) {
            cleanup();
          }
        } catch (Throwable e) {
          LoggerUtils.warnAndDebugDetails(logger, "unable to select channel keys", e);
        }
      }
    } catch (Throwable e) {
      LoggerUtils.errorAndDebugDetails(logger, "exception on cycle iteration", e);
    } finally {
      myCountDownLatch.countDown();
    }
  }

  private void cleanup() {
    lastCleanupTime = myTimeService.now();
    for (SelectionKey key : selector.keys()) {
      if (!key.isValid()) continue;
      myCleanupProcessor.processCleanup(key);
    }
  }

  private boolean needRunCleanup() {
    return (myTimeService.now() - lastCleanupTime) < myCleanupTimeoutMillis;
  }

  private void processWriteTasks() {

    WriteTask writeTask;
    while ((writeTask = myWriteQueue.poll()) != null) {
      if (stop || Thread.currentThread().isInterrupted()) {
        return;
      }
      logger.debug("try register channel for write. Write task is {}", writeTask);
      SocketChannel socketChannel = (SocketChannel) writeTask.getSocketChannel();
      if (!socketChannel.isOpen()) {
        writeTask.getListener().onWriteFailed();
        continue;
      }
      SelectionKey key = socketChannel.keyFor(selector);
      if (key == null) {
        logger.warn("unable to find key for channel {}", socketChannel);
        writeTask.getListener().onWriteFailed();
        continue;
      }
      Object attachment = key.attachment();
      if (!(attachment instanceof ReadWriteAttachment)) {
        logger.error("incorrect attachment {} for channel {}", attachment, socketChannel);
        writeTask.getListener().onWriteFailed();
        continue;
      }
      ReadWriteAttachment keyAttachment = (ReadWriteAttachment) attachment;
      if (keyAttachment.getWriteTasks().offer(writeTask)) {
        key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
      } else {
        writeTask.getListener().onWriteFailed();
      }
    }
  }

  private void connectToPeersFromQueue() {
    ConnectTask connectTask;
    while ((connectTask = myConnectQueue.poll()) != null) {
      if (stop || Thread.currentThread().isInterrupted()) {
        return;
      }
      logger.debug("try connect to peer. Connect task is {}", connectTask);
      try {
        SocketChannel socketChannel = SocketChannel.open();
        socketChannel.configureBlocking(false);
        socketChannel.register(selector, SelectionKey.OP_CONNECT, connectTask);
        socketChannel.connect(new InetSocketAddress(connectTask.getHost(), connectTask.getPort()));
      } catch (IOException e) {
        LoggerUtils.warnAndDebugDetails(logger, "unable connect. Connect task is {}", connectTask, e);
      }
    }
  }

  public void stop() {
    stop = true;
  }

  private void processSelectedKeys() {
    Set<SelectionKey> selectionKeys = selector.selectedKeys();
    for (SelectionKey key : selectionKeys) {
      if (stop || Thread.currentThread().isInterrupted()) {
        return;
      }
      try {
        processSelectedKey(key);
      } catch (Exception e) {
        LoggerUtils.warnAndDebugDetails(logger, "error in processing key. Close channel for this key...", e);
        try {
          key.channel().close();
        } catch (IOException ioe) {
          LoggerUtils.errorAndDebugDetails(logger, "unable close bad channel", ioe);
        }
      }
    }
    selectionKeys.clear();
  }

  private void processSelectedKey(SelectionKey key) throws IOException {
    if (!key.isValid()) {
      logger.info("Key for channel {} is invalid. Skipping", key.channel());
      return;
    }
    myCleanupProcessor.processSelected(key);
    for (KeyProcessor keyProcessor : myKeyProcessors) {
      if (keyProcessor.accept(key)) {
        keyProcessor.process(key);
      }
    }
  }

  public boolean offerConnect(ConnectTask connectTask, int timeout, TimeUnit timeUnit) {
    return addTaskToQueue(connectTask, timeout, timeUnit, myConnectQueue);
  }

  public boolean offerWrite(WriteTask writeTask, int timeout, TimeUnit timeUnit) {
    return addTaskToQueue(writeTask, timeout, timeUnit, myWriteQueue);
  }

  private <T> boolean addTaskToQueue(T task, int timeout, TimeUnit timeUnit, BlockingQueue<T> queue) {
    try {
      if (queue.offer(task, timeout, timeUnit)) {
        logger.debug("added task {}. Wake up selector", task);
        selector.wakeup();
        return true;
      }
    } catch (InterruptedException e) {
      logger.debug("Task {} interrupted before was added to queue", task);
    }
    logger.debug("Task {} was not added", task);
    return false;
  }
}
