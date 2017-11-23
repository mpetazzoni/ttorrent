package com.turn.ttorrent.client.network;

import com.turn.ttorrent.common.LoggerUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.*;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class ConnectionWorker implements Runnable {

  private static final Logger logger = LoggerFactory.getLogger(ConnectionWorker.class);
  private volatile boolean stop = false;
  private final Selector selector;
  private final BlockingQueue<ConnectTask> myConnectQueue;
  private final ServerSocketChannel myServerSocketChannel;

  public ConnectionWorker(Selector selector, ServerSocketChannel myServerSocketChannel) {
    this.selector = selector;
    this.myServerSocketChannel = myServerSocketChannel;
    this.myConnectQueue = new LinkedBlockingQueue<ConnectTask>(100);
  }

  @Override
  public void run() {

    try {
      while (!stop && (!Thread.currentThread().isInterrupted())) {
        try {
          logger.trace("try select keys from selector");
          int selected;
          try {
            selected = selector.select();// TODO: 11/13/17 timeout
          } catch (ClosedSelectorException e) {
            break;
          }
          connectToPeersFromQueue();
          logger.trace("select keys from selector. Keys count is " + selected);
          if (selected == 0) {
            continue;
          }
          processSelectedKeys();
        } catch (Throwable e) {
          LoggerUtils.warnAndDebugDetails(logger, "unable to select channel keys", e);
        }
      }
    } catch (Throwable e) {
      LoggerUtils.errorAndDebugDetails(logger, "exception on cycle iteration", e);
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
    if (key.isAcceptable()) {
      AcceptableKeyProcessor acceptableKeyProcessor = new AcceptableKeyProcessor(selector, myServerSocketChannel.getLocalAddress().toString());
      acceptableKeyProcessor.process(key);
    }
    if (key.isConnectable()) {
      ConnectableKeyProcessor connectableKeyProcessor = new ConnectableKeyProcessor(selector);
      connectableKeyProcessor.process(key);
    }

    if (key.isReadable()) {
      ReadableKeyProcessor readableKeyProcessor = new ReadableKeyProcessor(myServerSocketChannel.getLocalAddress().toString());
      readableKeyProcessor.process(key);
    }
  }

  public boolean connect(ConnectTask connectTask, int timeout, TimeUnit timeUnit) {
    try {
      if (myConnectQueue.offer(connectTask, timeout, timeUnit)) {
        logger.debug("added connect task {}. Wake up selector", connectTask);
        selector.wakeup();
        return true;
      }
    } catch (InterruptedException e) {
      logger.debug("connect task interrupted before address was added to queue");
    }
    logger.debug("connect task {} was not added", connectTask);
    return false;
  }
}
