package com.turn.ttorrent.client.network;

import com.turn.ttorrent.client.Client;
import com.turn.ttorrent.common.LoggerUtils;
import com.turn.ttorrent.common.Peer;
import com.turn.ttorrent.common.Torrent;
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

public class ConnectionReceiver implements Runnable, Closeable {

  private static final Logger logger = LoggerFactory.getLogger(ConnectionReceiver.class);

  public static final int PORT_RANGE_START = 6881;
  public static final int PORT_RANGE_END = 6889;

  private final Selector selector;
  private final InetAddress inetAddress;
  private final Client client;
  private ServerSocketChannel myServerSocketChannel;

  public ConnectionReceiver(Selector selector, InetAddress inetAddress, Client client) throws IOException {
    this.selector = selector;
    this.inetAddress = inetAddress;
    myServerSocketChannel = selector.provider().openServerSocketChannel();
    this.client = client;
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
        client.peersStorage.setSelf(new Peer(tryAddress, ByteBuffer.wrap(idBytes)));
        return;
      } catch (IOException e) {
        //try next port
        logger.debug("Could not bind to port {}, trying next port...", port);
      }
    }
    throw new IOException("No available port for the BitTorrent client!");
  }

  @Override
  public void run() {

    try {
      init();
      Peer self = client.peersStorage.getSelf();
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

    while (!Thread.interrupted()) {
      int selected = -1;
      try {
        selected = selector.select();// TODO: 11/13/17 timeout
      } catch (IOException e) {
        LoggerUtils.warnAndDebugDetails(logger, "unable to select channel keys", e);
      }
      logger.trace("select keys from selector. Keys count is " + selected);
      if (selected < 0) {
        logger.info("selected count less that zero");
      }
      if (selected == 0) {
        continue;
      }
      processSelectedKeys();
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
      } catch (IOException e) {
        LoggerUtils.warnAndDebugDetails(logger, "error in processing key", e);
        // TODO: 11/13/17 close channel?
      }
    }
    selectionKeys.clear();
  }

  private void processSelectedKey(SelectionKey key) throws IOException {
    if (key.isAcceptable()) {
      SocketChannel socketChannel = myServerSocketChannel.accept();
      logger.trace("server {} get new connection from " + socketChannel.socket(), new Object[]{myServerSocketChannel.getLocalAddress()});
      StateChannelListener stateChannelListener = new StateChannelListener(client);
      stateChannelListener.onConnectionAccept(socketChannel);
      socketChannel.configureBlocking(false);
      socketChannel.register(selector, SelectionKey.OP_READ, stateChannelListener);
    }
    if (key.isReadable()) {
      SelectableChannel channel = key.channel();
      if (!(channel instanceof SocketChannel)) {
        logger.info("incorrect instance of channel. Close connection with it");
        channel.close();
        return;
      }
      SocketChannel socketChannel = (SocketChannel) channel;
      logger.trace("server {} get new data from " + socketChannel.socket(), new Object[]{myServerSocketChannel.getLocalAddress()});
      ChannelListener channelListener = (ChannelListener) key.attachment();
      channelListener.onNewDataAvailable(socketChannel);
    }
  }
}
