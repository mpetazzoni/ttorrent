package com.turn.ttorrent.client.network;

import com.turn.ttorrent.client.network.keyProcessors.*;
import com.turn.ttorrent.common.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.*;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.turn.ttorrent.Constants.DEFAULT_CLEANUP_RUN_TIMEOUT_MILLIS;
import static com.turn.ttorrent.Constants.DEFAULT_SELECTOR_SELECT_TIMEOUT_MILLIS;

public class ConnectionManager {

  private static final Logger logger = LoggerFactory.getLogger(ConnectionManager.class);

  public static final int PORT_RANGE_START = 6881;
  public static final int PORT_RANGE_END = 6889;

  private final Selector selector;
  private final InetAddress inetAddress;
  private final ChannelListenerFactory channelListenerFactory;
  private final TimeService myTimeService;
  private volatile ConnectionWorker myConnectionWorker;
  private InetSocketAddress myBindAddress;
  private volatile ServerSocketChannel myServerSocketChannel;
  private final ExecutorService myExecutorService;
  private volatile Future<?> myWorkerFuture;
  private final NewConnectionAllower myIncomingConnectionAllower;
  private final NewConnectionAllower myOutgoingConnectionAllower;
  private final TimeoutStorage socketTimeoutStorage = new TimeoutStorageImpl();
  private final AtomicBoolean alreadyInit = new AtomicBoolean(false);

  public ConnectionManager(InetAddress inetAddress,
                           ChannelListenerFactory channelListenerFactory,
                           ExecutorService executorService,
                           TimeService timeService,
                           NewConnectionAllower newIncomingConnectionAllower,
                           NewConnectionAllower newOutgoingConnectionAllower) throws IOException {
    this.myExecutorService = executorService;
    this.selector = Selector.open();
    this.inetAddress = inetAddress;
    this.channelListenerFactory = channelListenerFactory;
    this.myTimeService = timeService;
    this.myIncomingConnectionAllower = newIncomingConnectionAllower;
    this.myOutgoingConnectionAllower = newOutgoingConnectionAllower;
  }

  public void initAndRunWorker() throws IOException {

    boolean wasInit = alreadyInit.getAndSet(true);

    if (wasInit) {
      throw new IllegalStateException("connection manager was already initialized");
    }

    myServerSocketChannel = selector.provider().openServerSocketChannel();
    myServerSocketChannel.configureBlocking(false);
    myBindAddress = null;
    for (int port = PORT_RANGE_START; port < PORT_RANGE_END; port++) {
      try {
        InetSocketAddress tryAddress = new InetSocketAddress(inetAddress, port);
        myServerSocketChannel.socket().bind(tryAddress);
        myServerSocketChannel.register(selector, SelectionKey.OP_ACCEPT, new AcceptAttachmentImpl(channelListenerFactory));
        myBindAddress = tryAddress;
        break;
      } catch (IOException e) {
        //try next port
        logger.debug("Could not bind to port {}, trying next port...", port);
      }
    }
    if (myBindAddress == null) {
      throw new IOException("No available port for the BitTorrent client!");
    }
    String serverName = myServerSocketChannel.getLocalAddress().toString();
    myConnectionWorker = new ConnectionWorker(selector, Arrays.asList(
            new AcceptableKeyProcessor(selector, serverName, myTimeService, myIncomingConnectionAllower, socketTimeoutStorage),
            new ConnectableKeyProcessor(selector, myTimeService, socketTimeoutStorage),
            new ReadableKeyProcessor(serverName),
            new WritableKeyProcessor()), DEFAULT_SELECTOR_SELECT_TIMEOUT_MILLIS, DEFAULT_CLEANUP_RUN_TIMEOUT_MILLIS,
            myTimeService,
            new CleanupKeyProcessor(myTimeService),
            myOutgoingConnectionAllower);
    myWorkerFuture = myExecutorService.submit(myConnectionWorker);
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


  public InetSocketAddress getBindAddress() {
    return myBindAddress;
  }

  public void close(int timeout, TimeUnit timeUnit) {
    logger.debug("try close connection manager...");
    boolean successfullyClosed = true;
    if (myConnectionWorker != null) {
      myWorkerFuture.cancel(true);
      myConnectionWorker.stop();
      try {
        boolean shutdownCorrectly = myConnectionWorker.getCountDownLatch().await(timeout, timeUnit);
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
    if (myConnectionWorker != null) {
      myConnectionWorker.setCleanupTimeout(timeoutMillis);
    }
  }

  public void setSocketConnectionTimeout(long timeoutMillis) {
    socketTimeoutStorage.setTimeout(timeoutMillis);
  }

  public void closeChannel(Channel channel) throws IOException {
    channel.close();
  }
}
