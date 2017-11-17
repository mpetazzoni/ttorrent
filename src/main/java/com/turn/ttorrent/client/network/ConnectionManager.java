package com.turn.ttorrent.client.network;

import com.turn.ttorrent.client.Client;
import com.turn.ttorrent.client.peer.PeerActivityListener;
import com.turn.ttorrent.common.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
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

public class ConnectionManager implements Runnable, Closeable {

  private static final Logger logger = LoggerFactory.getLogger(ConnectionManager.class);

  public static final int PORT_RANGE_START = 6881;
  public static final int PORT_RANGE_END = 6889;

  private final Selector selector;
  private final InetAddress inetAddress;
  private final PeersStorageFactory peersStorageFactory;
  private final ChannelListenerFactory channelListenerFactory;
  private ServerSocketChannel myServerSocketChannel;
  private final BlockingQueue<Peer> myConnectQueue;

  public ConnectionManager(InetAddress inetAddress,
                           PeersStorageFactory peersStorageFactory,
                           TorrentsStorageFactory torrentsStorageFactory,
                           PeerActivityListener peerActivityListener) throws IOException {
    this(inetAddress, peersStorageFactory, new ChannelListenerFactoryImpl(peersStorageFactory, torrentsStorageFactory, peerActivityListener));
  }

  public ConnectionManager(InetAddress inetAddress,
                           PeersStorageFactory peersStorageFactory,
                           ChannelListenerFactory channelListenerFactory) throws IOException {
    this.selector = Selector.open();
    this.inetAddress = inetAddress;
    this.peersStorageFactory = peersStorageFactory;
    this.channelListenerFactory = channelListenerFactory;
    this.myConnectQueue = new LinkedBlockingQueue<Peer>(100);
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
        peersStorageFactory.getPeersStorage().setSelf(new Peer(tryAddress, ByteBuffer.wrap(idBytes)));
        return;
      } catch (IOException e) {
        //try next port
        logger.debug("Could not bind to port {}, trying next port...", port);
      }
    }
    throw new IOException("No available port for the BitTorrent client!");
  }

  public void connectTo(Peer peer) {
    try {
      myConnectQueue.offer(peer, 1, TimeUnit.SECONDS);
      selector.wakeup();
    } catch (InterruptedException e) {
      logger.debug("connect task interrupted before address was added to queue");
    }
  }

  @Override
  public void run() {

    try {
      init();
      Peer self = peersStorageFactory.getPeersStorage().getSelf();
      logger.info("BitTorrent client [{}] started and " +
                      "listening at {}:{}...",
              new Object[]{
                      self.getShortHexPeerId(),
                      self.getIp(),
                      self.getPort()
              });
    } catch (IOException e) {
      LoggerUtils.warnAndDebugDetails(logger, "error in initialization server channel", e);
      return;
    }

    while (!Thread.currentThread().isInterrupted()) {
      try {
        int selected = selector.select();// TODO: 11/13/17 timeout
        Peer peer;
        try {
          while ((peer = myConnectQueue.poll(10, TimeUnit.MILLISECONDS))!= null) {
            SocketChannel socketChannel = SocketChannel.open();
            socketChannel.configureBlocking(false);
            socketChannel.register(selector, SelectionKey.OP_CONNECT, peer);
            socketChannel.connect(new InetSocketAddress(peer.getIp(), peer.getPort()));
          }
        } catch (InterruptedException e) {
          break;
        }
        logger.trace("select keys from selector. Keys count is " + selected);
        if (selected == 0) {
          continue;
        }

        processSelectedKeys();
      } catch (IOException e) {
        LoggerUtils.warnAndDebugDetails(logger, "unable to select channel keys", e);
      }

    }
    try {
      close();
    } catch (IOException e) {
      LoggerUtils.warnAndDebugDetails(logger, "unable to close connection receiver", e);
    }
  }

  @Override
  public void close() throws IOException {
    this.myServerSocketChannel.close();
    // TODO: 11/13/17 close all opened connections with peers
  }

  private void processSelectedKeys() {
    Set<SelectionKey> selectionKeys = selector.selectedKeys();
    for (SelectionKey key : selectionKeys) {
      try {
        processSelectedKey(key);
      } catch (Exception e) {
        LoggerUtils.warnAndDebugDetails(logger, "error in processing key. Close channel for this key...", e);
        try {
          key.channel().close();
        } catch (IOException ioe) {
          LoggerUtils.warnAndDebugDetails(logger, "unable close bad channel", ioe);
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
      if (!(attachment instanceof Peer)) {
        logger.warn("incorrect instance of attachment for channel {}", new Object[]{socketChannel.socket()});
        socketChannel.close();
        return;
      }
      stateChannelListener.onConnected(socketChannel, (Peer)attachment);
      socketChannel.configureBlocking(false);
      socketChannel.register(selector, SelectionKey.OP_READ, stateChannelListener);
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
