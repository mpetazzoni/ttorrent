package com.turn.ttorrent.client.network;

import com.turn.ttorrent.client.Client;
import com.turn.ttorrent.client.peer.PeerActivityListener;
import com.turn.ttorrent.common.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;

public class ConnectionManager implements Runnable {

  private static final Logger logger = LoggerFactory.getLogger(ConnectionManager.class);

  public static final int PORT_RANGE_START = 6881;
  public static final int PORT_RANGE_END = 6889;

  private final Selector selector;
  private final InetAddress inetAddress;
  private final PeersStorageProvider peersStorageProvider;
  private final ChannelListenerFactory channelListenerFactory;
  private ServerSocketChannel myServerSocketChannel;
  private InetSocketAddress myBindAddress;
  private final BlockingQueue<ConnectTask> myConnectQueue;
  private final ExecutorService myExecutorService;
  private Future<?> myWorkerFuture;

  public ConnectionManager(InetAddress inetAddress,
                           PeersStorageProvider peersStorageProvider,
                           TorrentsStorageProvider torrentsStorageProvider,
                           PeerActivityListener peerActivityListener) throws IOException {
    this(inetAddress, peersStorageProvider, new ChannelListenerFactoryImpl(peersStorageProvider, torrentsStorageProvider, peerActivityListener));
  }

  public ConnectionManager(InetAddress inetAddress,
                           PeersStorageProvider peersStorageProvider,
                           ChannelListenerFactory channelListenerFactory) throws IOException {
    this.myExecutorService = Executors.newSingleThreadExecutor();
    this.selector = Selector.open();
    this.inetAddress = inetAddress;
    this.peersStorageProvider = peersStorageProvider;
    this.channelListenerFactory = channelListenerFactory;
    this.myConnectQueue = new LinkedBlockingQueue<ConnectTask>(100);
  }

  public void initAndRunWorker() throws IOException {
    myServerSocketChannel = selector.provider().openServerSocketChannel();
    myServerSocketChannel.configureBlocking(false);

    for (int port = PORT_RANGE_START; port < PORT_RANGE_END; port++) {
      try {
        InetSocketAddress tryAddress = new InetSocketAddress(inetAddress, port);
        myServerSocketChannel.socket().bind(tryAddress);
        myServerSocketChannel.register(selector, SelectionKey.OP_ACCEPT);
        this.myBindAddress = tryAddress;
        break;
      } catch (IOException e) {
        //try next port
        logger.debug("Could not bind to port {}, trying next port...", port);
      }
    }
    if (this.myBindAddress == null) {
      throw new IOException("No available port for the BitTorrent client!");
    }
    final String id = Client.BITTORRENT_ID_PREFIX + UUID.randomUUID().toString().split("-")[4];
    byte[] idBytes = id.getBytes(Torrent.BYTE_ENCODING);
    Peer self = new Peer(this.myBindAddress, ByteBuffer.wrap(idBytes));
    peersStorageProvider.getPeersStorage().setSelf(self);
    myWorkerFuture = myExecutorService.submit(this);// TODO: 11/22/17 move runnable part to separate class e.g. ConnectionWorker
    logger.info("BitTorrent client [{}] started and " +
                    "listening at {}:{}...",
            new Object[]{
                    self.getShortHexPeerId(),
                    self.getIp(),
                    self.getPort()
            });
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

  public InetSocketAddress getBindAddress() {
    return myBindAddress;
  }

  @Override
  public void run() {

    try {
      while (!Thread.currentThread().isInterrupted()) {
        try {
          logger.trace("try select keys from selector");
          int selected;
          try {
            selected = selector.select();// TODO: 11/13/17 timeout
          } catch (ClosedSelectorException e) {
            break;
          }
          logger.trace("selected keys, try connect to peers from queue");
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
      if (Thread.currentThread().isInterrupted()) {
        return;
      }
      logger.debug("try connect to peer. Connect task is {}", connectTask);
      try {
        SocketChannel socketChannel = SocketChannel.open();
        socketChannel.configureBlocking(false);
        socketChannel.register(selector, SelectionKey.OP_CONNECT, connectTask);
        socketChannel.connect(new InetSocketAddress(connectTask.getHost(), connectTask.getPort()));
      } catch (IOException e) {
        logger.warn("unable connect. Connect task is {}", connectTask);
        logger.debug("", e);
      }
    }
  }

  public void close(boolean await) {
    logger.debug("try close connection manager...");
    boolean successfullyClosed = true;
    myWorkerFuture.cancel(true);
    myExecutorService.shutdown();
    if (await) {
      try {
        boolean shutdownCorrectly = myExecutorService.awaitTermination(1, TimeUnit.MINUTES);
        if (!shutdownCorrectly) {
          successfullyClosed = false;
          logger.warn("unable to terminate executor service in specified timeout");
        }
      } catch (InterruptedException e) {
        successfullyClosed = false;
        LoggerUtils.warnAndDebugDetails(logger, "unable to await termination executor service, thread was interrupted", e);
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
      logger.error("it was received some errors in stop process of connection manager and worker");
    }
  }

  private void processSelectedKeys() {
    Set<SelectionKey> selectionKeys = selector.selectedKeys();
    for (SelectionKey key : selectionKeys) {
      if (Thread.currentThread().isInterrupted()) {
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
      SelectableChannel channel = key.channel();
      if (!(channel instanceof ServerSocketChannel)) {
        logger.error("incorrect instance of server channel. Can not accept connections");
        channel.close();
        return;
      }
      SocketChannel socketChannel = ((ServerSocketChannel) key.channel()).accept();
      logger.trace("server {} get new connection from {}", new Object[]{myServerSocketChannel.getLocalAddress(), socketChannel.socket()});

      ConnectionListener stateConnectionListener = channelListenerFactory.newChannelListener();
      stateConnectionListener.onConnectionEstablished(socketChannel);
      socketChannel.configureBlocking(false);
      socketChannel.register(selector, SelectionKey.OP_READ, stateConnectionListener);
    }
    if (key.isConnectable()) {
      SelectableChannel channel = key.channel();
      if (!(channel instanceof SocketChannel)) {
        logger.warn("incorrect instance of channel. Close connection with it");
        channel.close();
        return;
      }
      SocketChannel socketChannel = (SocketChannel) channel;
      Object attachment = key.attachment();
      if (!(attachment instanceof ConnectTask)) {
        logger.warn("incorrect instance of attachment for channel {}", new Object[]{socketChannel.socket()});
        socketChannel.close();
        return;
      }
      boolean isConnectFinished = socketChannel.finishConnect();
      if (!isConnectFinished) {
        return;
      }
      socketChannel.configureBlocking(false);
      ConnectionListener connectionListener = ((ConnectTask) attachment).getConnectionListener();
      socketChannel.register(selector, SelectionKey.OP_READ, connectionListener);
      connectionListener.onConnectionEstablished(socketChannel);
    }

    if (key.isReadable()) {
      SelectableChannel channel = key.channel();
      if (!(channel instanceof SocketChannel)) {
        logger.warn("incorrect instance of channel. Close connection with it");
        channel.close();
        return;
      }

      SocketChannel socketChannel = (SocketChannel) channel;
      logger.trace("server {} get new data from {}", new Object[]{myServerSocketChannel.getLocalAddress(), socketChannel.socket()});

      Object attachment = key.attachment();
      if (!(attachment instanceof ConnectionListener)) {
        logger.warn("incorrect instance of attachment for channel {}", new Object[]{socketChannel.socket()});
        socketChannel.close();
        return;
      }
      ConnectionListener connectionListener = (ConnectionListener) attachment;
      connectionListener.onNewDataAvailable(socketChannel);
    }
  }
}
