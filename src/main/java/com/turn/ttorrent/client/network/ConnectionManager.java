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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class ConnectionManager implements Runnable {

  private static final Logger logger = LoggerFactory.getLogger(ConnectionManager.class);

  public static final int PORT_RANGE_START = 6881;
  public static final int PORT_RANGE_END = 6889;

  private final Selector selector;
  private final InetAddress inetAddress;
  private final PeersStorageProvider peersStorageProvider;
  private final ChannelListenerFactory channelListenerFactory;
  private ServerSocketChannel myServerSocketChannel;
  private final BlockingQueue<ConnectTask> myConnectQueue;

  public ConnectionManager(InetAddress inetAddress,
                           PeersStorageProvider peersStorageProvider,
                           TorrentsStorageProvider torrentsStorageProvider,
                           PeerActivityListener peerActivityListener) throws IOException {
    this(inetAddress, peersStorageProvider, new ChannelListenerFactoryImpl(peersStorageProvider, torrentsStorageProvider, peerActivityListener));
  }

  public ConnectionManager(InetAddress inetAddress,
                           PeersStorageProvider peersStorageProvider,
                           ChannelListenerFactory channelListenerFactory) throws IOException {
    this.selector = Selector.open();
    this.inetAddress = inetAddress;
    this.peersStorageProvider = peersStorageProvider;
    this.channelListenerFactory = channelListenerFactory;
    this.myConnectQueue = new LinkedBlockingQueue<ConnectTask>(100);
    myServerSocketChannel = selector.provider().openServerSocketChannel();
  }

  private void init() throws IOException {
    myServerSocketChannel.configureBlocking(false);

    for (int port = PORT_RANGE_START; port < PORT_RANGE_END; port++) {
      try {
        InetSocketAddress tryAddress = new InetSocketAddress(inetAddress, port);
        myServerSocketChannel.socket().bind(tryAddress);
        myServerSocketChannel.register(selector, SelectionKey.OP_ACCEPT);
        final String id = Client.BITTORRENT_ID_PREFIX + UUID.randomUUID().toString().split("-")[4];
        byte[] idBytes = id.getBytes(Torrent.BYTE_ENCODING);
        peersStorageProvider.getPeersStorage().setSelf(new Peer(tryAddress, ByteBuffer.wrap(idBytes)));
        return;
      } catch (IOException e) {
        //try next port
        logger.debug("Could not bind to port {}, trying next port...", port);
      }
    }
    throw new IOException("No available port for the BitTorrent client!");
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

  @Override
  public void run() {

    try {
      init();
      Peer self = peersStorageProvider.getPeersStorage().getSelf();
      logger.info("BitTorrent client [{}] started and " +
                      "listening at {}:{}...",
              new Object[]{
                      self.getShortHexPeerId(),
                      self.getIp(),
                      self.getPort()
              });
    } catch (IOException e) {
      LoggerUtils.errorAndDebugDetails(logger, "error in initialization server channel", e);
      close();
      return;
    }
    try {
      while (!Thread.currentThread().isInterrupted()) {
        try {
          logger.trace("try select keys from selector");
          int selected = selector.select();// TODO: 11/13/17 timeout
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
    } finally {
      close();
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

  private void close() {
    try {
      this.myServerSocketChannel.close();
    } catch (Throwable e) {
      LoggerUtils.errorAndDebugDetails(logger, "unable to close server socket channel", e);
    }
    for (SelectionKey key : this.selector.keys()) {
      try {
        if (key.isValid()) {
          key.channel().close();
        }
      } catch (Throwable e) {
        logger.error("unable to close socket channel {}", key.channel());
        logger.debug("", e);
      }
    }
    try {
      this.selector.close();
    } catch (Throwable e) {
      LoggerUtils.errorAndDebugDetails(logger, "unable to close selector channel", e);
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

      ChannelListener stateChannelListener = channelListenerFactory.newChannelListener();
      stateChannelListener.onConnectionAccept(socketChannel);
      socketChannel.configureBlocking(false);
      socketChannel.register(selector, SelectionKey.OP_READ, stateChannelListener);
    }
    if (key.isConnectable()) {
      SelectableChannel channel = key.channel();
      if (!(channel instanceof SocketChannel)) {
        logger.warn("incorrect instance of channel. Close connection with it");
        channel.close();
        return;
      }
      SocketChannel socketChannel = (SocketChannel) channel;
      ChannelListener stateChannelListener = channelListenerFactory.newChannelListener();
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
      socketChannel.register(selector, SelectionKey.OP_READ, stateChannelListener);
      stateChannelListener.onConnected(socketChannel, (ConnectTask) attachment);
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
      if (!(attachment instanceof ChannelListener)) {
        logger.warn("incorrect instance of attachment for channel {}", new Object[]{socketChannel.socket()});
        socketChannel.close();
        return;
      }
      ChannelListener channelListener = (ChannelListener) attachment;
      channelListener.onNewDataAvailable(socketChannel);
    }
  }
}
