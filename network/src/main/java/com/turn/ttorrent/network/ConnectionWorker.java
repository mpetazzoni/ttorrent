package com.turn.ttorrent.network;

import com.turn.ttorrent.common.LoggerUtils;
import com.turn.ttorrent.common.TimeService;
import com.turn.ttorrent.common.TorrentLoggerFactory;
import com.turn.ttorrent.network.keyProcessors.CleanupProcessor;
import com.turn.ttorrent.network.keyProcessors.KeyProcessor;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.*;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class ConnectionWorker implements Runnable {

  private static final Logger logger = TorrentLoggerFactory.getLogger();
  private static final String SELECTOR_THREAD_NAME = "Torrent channels manager thread";
  private volatile boolean stop = false;
  private final Selector selector;
  private final BlockingQueue<ConnectTask> myConnectQueue;
  private final BlockingQueue<WriteTask> myWriteQueue;
  private final Semaphore mySemaphore;
  private final List<KeyProcessor> myKeyProcessors;
  private final TimeService myTimeService;
  private long lastCleanupTime;
  private volatile int mySelectorTimeoutMillis;
  private volatile long myCleanupTimeoutMillis;
  private final CleanupProcessor myCleanupProcessor;
  private final NewConnectionAllower myNewConnectionAllower;

  ConnectionWorker(Selector selector,
                   List<KeyProcessor> keyProcessors,
                   int selectorTimeoutMillis,
                   int cleanupTimeoutMillis,
                   TimeService timeService,
                   CleanupProcessor cleanupProcessor,
                   NewConnectionAllower myNewConnectionAllower) {
    this.selector = selector;
    this.myTimeService = timeService;
    this.lastCleanupTime = timeService.now();
    this.mySelectorTimeoutMillis = selectorTimeoutMillis;
    this.myCleanupTimeoutMillis = cleanupTimeoutMillis;
    this.myCleanupProcessor = cleanupProcessor;
    this.myNewConnectionAllower = myNewConnectionAllower;
    this.mySemaphore = new Semaphore(1);
    this.myConnectQueue = new LinkedBlockingQueue<ConnectTask>(100);
    this.myKeyProcessors = keyProcessors;
    this.myWriteQueue = new LinkedBlockingQueue<WriteTask>(100);
  }

  @Override
  public void run() {

    try {
      mySemaphore.acquire();
    } catch (InterruptedException e) {
      return;
    }

    final String oldName = Thread.currentThread().getName();

    try {

      Thread.currentThread().setName(SELECTOR_THREAD_NAME);

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
          if (selected != 0) {
            processSelectedKeys();
          }
          if (needRunCleanup()) {
            cleanup();
          }
        } catch (Throwable e) {
          LoggerUtils.warnAndDebugDetails(logger, "unable to select channel keys. Error message {}", e.getMessage(), e);
        }
      }
    } catch (Throwable e) {
      LoggerUtils.errorAndDebugDetails(logger, "exception on cycle iteration", e);
    } finally {
      Thread.currentThread().setName(oldName);
      mySemaphore.release();
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
    return (myTimeService.now() - lastCleanupTime) > myCleanupTimeoutMillis;
  }

  private void processWriteTasks() {

    final Iterator<WriteTask> iterator = myWriteQueue.iterator();
    while (iterator.hasNext()) {
      WriteTask writeTask = iterator.next();
      if (stop || Thread.currentThread().isInterrupted()) {
        return;
      }
      logger.trace("try register channel for write. Write task is {}", writeTask);
      SocketChannel socketChannel = (SocketChannel) writeTask.getSocketChannel();
      if (!socketChannel.isOpen()) {
        iterator.remove();
        writeTask.getListener().onWriteFailed(getDefaultWriteErrorMessageWithSuffix(socketChannel, "Channel is not open"), null);
        continue;
      }
      SelectionKey key = socketChannel.keyFor(selector);
      if (key == null) {
        logger.warn("unable to find key for channel {}", socketChannel);
        iterator.remove();
        writeTask.getListener().onWriteFailed(getDefaultWriteErrorMessageWithSuffix(socketChannel, "Can not find key for the channel"), null);
        continue;
      }
      Object attachment = key.attachment();
      if (!(attachment instanceof WriteAttachment)) {
        logger.error("incorrect attachment {} for channel {}", attachment, socketChannel);
        iterator.remove();
        writeTask.getListener().onWriteFailed(getDefaultWriteErrorMessageWithSuffix(socketChannel, "Incorrect attachment instance for the key"), null);
        continue;
      }
      WriteAttachment keyAttachment = (WriteAttachment) attachment;
      if (keyAttachment.getWriteTasks().offer(writeTask)) {
        iterator.remove();
        try {
          key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
        } catch (CancelledKeyException e) {
          writeTask.getListener().onWriteFailed(getDefaultWriteErrorMessageWithSuffix(socketChannel, "Key is cancelled"), null);
        }
      }
    }
  }

  private String getDefaultWriteErrorMessageWithSuffix(SocketChannel socketChannel, String suffix) {
    return "unable write data to channel " + socketChannel + ". " + suffix;
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

  public boolean stop(int timeout, TimeUnit timeUnit) throws InterruptedException {
    stop = true;
    if (timeout <= 0) {
      return true;
    }
    return mySemaphore.tryAcquire(timeout, timeUnit);
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
        logger.warn("error {} in processing key. Close channel {}", e.getMessage(), key.channel());
        logger.debug("", e);
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
    logger.trace("try process key for channel {}", key.channel());
    myCleanupProcessor.processSelected(key);
    if (!key.channel().isOpen()) {
      key.cancel();
      return;
    }
    for (KeyProcessor keyProcessor : myKeyProcessors) {
      if (keyProcessor.accept(key)) {
        keyProcessor.process(key);
      }
    }
  }

  public boolean offerConnect(ConnectTask connectTask, int timeout, TimeUnit timeUnit) {
    if (!myNewConnectionAllower.isNewConnectionAllowed()) {
      logger.info("can not add connect task {} to queue. New connection is not allowed", connectTask);
      return false;
    }
    return addTaskToQueue(connectTask, timeout, timeUnit, myConnectQueue);
  }

  public boolean offerWrite(WriteTask writeTask, int timeout, TimeUnit timeUnit) {
    boolean done = addTaskToQueue(writeTask, timeout, timeUnit, myWriteQueue);
    if (!done) {
      writeTask.getListener().onWriteFailed("unable add task " + writeTask + " to the queue. Maybe queue is overload", null);
    }
    return done;
  }

  private <T> boolean addTaskToQueue(T task, int timeout, TimeUnit timeUnit, BlockingQueue<T> queue) {
    try {
      if (queue.offer(task, timeout, timeUnit)) {
        logger.trace("added task {}. Wake up selector", task);
        selector.wakeup();
        return true;
      }
    } catch (InterruptedException e) {
      logger.debug("Task {} interrupted before was added to queue", task);
    }
    logger.debug("Task {} was not added", task);
    return false;
  }

  void setCleanupTimeout(long timeoutMillis) {
    this.myCleanupTimeoutMillis = timeoutMillis;
  }

  void setSelectorSelectTimeout(int timeout) {
    mySelectorTimeoutMillis = timeout;
  }

}
