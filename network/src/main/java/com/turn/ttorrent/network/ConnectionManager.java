package com.turn.ttorrent.network;

import com.turn.ttorrent.common.LoggerUtils;
import com.turn.ttorrent.common.TimeService;
import com.turn.ttorrent.common.TorrentLoggerFactory;
import com.turn.ttorrent.network.keyProcessors.*;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.channels.Channel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.util.Arrays;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.turn.ttorrent.Constants.DEFAULT_CLEANUP_RUN_TIMEOUT_MILLIS;
import static com.turn.ttorrent.Constants.DEFAULT_SELECTOR_SELECT_TIMEOUT_MILLIS;

public class ConnectionManager {

  private static final Logger logger = TorrentLoggerFactory.getLogger();

  private final Selector selector;
  private final TimeService myTimeService;
  private volatile ConnectionWorker myConnectionWorker;
  private int myBindPort;
  private final ConnectionManagerContext myContext;
  private volatile ServerSocketChannel myServerSocketChannel;
  private volatile Future<?> myWorkerFuture;
  private final NewConnectionAllower myIncomingConnectionAllower;
  private final NewConnectionAllower myOutgoingConnectionAllower;
  private final TimeoutStorage socketTimeoutStorage = new TimeoutStorageImpl();
  private final AtomicBoolean alreadyInit = new AtomicBoolean(false);
  private final AtomicInteger mySendBufferSize;
  private final AtomicInteger myReceiveBufferSize;

  public ConnectionManager(ConnectionManagerContext context,
                           TimeService timeService,
                           NewConnectionAllower newIncomingConnectionAllower,
                           NewConnectionAllower newOutgoingConnectionAllower,
                           SelectorFactory selectorFactory,
                           AtomicInteger mySendBufferSize,
                           AtomicInteger myReceiveBufferSize) throws IOException {
    this.mySendBufferSize = mySendBufferSize;
    this.myReceiveBufferSize = myReceiveBufferSize;
    this.selector = selectorFactory.newSelector();
    this.myTimeService = timeService;
    myContext = context;
    this.myIncomingConnectionAllower = newIncomingConnectionAllower;
    this.myOutgoingConnectionAllower = newOutgoingConnectionAllower;
  }

  public void initAndRunWorker(ServerChannelRegister serverChannelRegister) throws IOException {

    boolean wasInit = alreadyInit.getAndSet(true);

    if (wasInit) {
      throw new IllegalStateException("connection manager was already initialized");
    }

    myServerSocketChannel = serverChannelRegister.channelFor(selector);
    myServerSocketChannel.register(selector, SelectionKey.OP_ACCEPT, new AcceptAttachmentImpl(myContext));
    myBindPort = myServerSocketChannel.socket().getLocalPort();
    String serverName = myServerSocketChannel.socket().toString();
    myConnectionWorker = new ConnectionWorker(selector, Arrays.asList(
            new InvalidKeyProcessor(),
            new AcceptableKeyProcessor(selector, serverName, myTimeService, myIncomingConnectionAllower, socketTimeoutStorage,
                    mySendBufferSize, myReceiveBufferSize),
            new ConnectableKeyProcessor(selector, myTimeService, socketTimeoutStorage,
                    mySendBufferSize, myReceiveBufferSize),
            new ReadableKeyProcessor(serverName),
            new WritableKeyProcessor()), DEFAULT_SELECTOR_SELECT_TIMEOUT_MILLIS, DEFAULT_CLEANUP_RUN_TIMEOUT_MILLIS,
            myTimeService,
            new CleanupKeyProcessor(myTimeService),
            myOutgoingConnectionAllower);
    myWorkerFuture = myContext.getExecutor().submit(myConnectionWorker);
  }

  public void setSelectorSelectTimeout(int timeout) {
    ConnectionWorker workerLocal = myConnectionWorker;
    checkThatWorkerIsInit(workerLocal);
    workerLocal.setSelectorSelectTimeout(timeout);
  }

  private void checkThatWorkerIsInit(ConnectionWorker worker) {
    if (worker == null) throw new IllegalStateException("Connection manager is not initialized!");
  }

  public boolean offerConnect(ConnectTask connectTask, int timeout, TimeUnit timeUnit) {
    if (myConnectionWorker == null) {
      return false;
    }
    return myConnectionWorker.offerConnect(connectTask, timeout, timeUnit);
  }

  public boolean offerWrite(WriteTask writeTask, int timeout, TimeUnit timeUnit) {
    if (myConnectionWorker == null) {
      return false;
    }
    return myConnectionWorker.offerWrite(writeTask, timeout, timeUnit);
  }


  public int getBindPort() {
    return myBindPort;
  }

  public void close(int timeout, TimeUnit timeUnit) {
    logger.debug("try close connection manager...");
    boolean successfullyClosed = true;
    if (myConnectionWorker != null) {
      myWorkerFuture.cancel(true);
      try {
        boolean shutdownCorrectly = myConnectionWorker.stop(timeout, timeUnit);
        if (!shutdownCorrectly) {
          successfullyClosed = false;
          logger.warn("unable to terminate worker in {} {}", timeout, timeUnit);
        }
      } catch (InterruptedException e) {
        successfullyClosed = false;
        LoggerUtils.warnAndDebugDetails(logger, "unable to await termination worker, thread was interrupted", e);
      }
    }
    try {
      this.myServerSocketChannel.close();
    } catch (Throwable e) {
      LoggerUtils.errorAndDebugDetails(logger, "unable to close server socket channel", e);
      successfullyClosed = false;
    }
    for (SelectionKey key : this.selector.keys()) {
      try {
        if (key.isValid()) {
          key.channel().close();
        }
      } catch (Throwable e) {
        logger.error("unable to close socket channel {}", key.channel());
        successfullyClosed = false;
        logger.debug("", e);
      }
    }
    try {
      this.selector.close();
    } catch (Throwable e) {
      LoggerUtils.errorAndDebugDetails(logger, "unable to close selector channel", e);
      successfullyClosed = false;
    }
    if (successfullyClosed) {
      logger.debug("connection manager is successfully closed");
    } else {
      logger.error("connection manager wasn't closed successfully");
    }
  }

  public void close() {
    close(1, TimeUnit.MINUTES);
  }

  public void setCleanupTimeout(long timeoutMillis) {
    ConnectionWorker workerLocal = myConnectionWorker;
    checkThatWorkerIsInit(workerLocal);
    workerLocal.setCleanupTimeout(timeoutMillis);
  }

  public void setSocketConnectionTimeout(long timeoutMillis) {
    socketTimeoutStorage.setTimeout(timeoutMillis);
  }

  public void closeChannel(Channel channel) throws IOException {
    channel.close();
  }
}
