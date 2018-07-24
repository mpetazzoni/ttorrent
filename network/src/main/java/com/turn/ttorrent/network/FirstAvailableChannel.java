package com.turn.ttorrent.network;

import com.turn.ttorrent.common.TorrentLoggerFactory;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;

public class FirstAvailableChannel implements ServerChannelRegister {

  private static final Logger logger = TorrentLoggerFactory.getLogger();

  private final int firstTryPort;
  private final int lastTryPort;

  public FirstAvailableChannel(int firstTryPort, int lastTryPort) {
    this.firstTryPort = firstTryPort;
    this.lastTryPort = lastTryPort;
  }

  @NotNull
  @Override
  public ServerSocketChannel channelFor(Selector selector) throws IOException {
    ServerSocketChannel myServerSocketChannel = selector.provider().openServerSocketChannel();
    myServerSocketChannel.configureBlocking(false);
    int bindPort = -1;
    for (int port = firstTryPort; port <= lastTryPort; port++) {
      try {
        InetSocketAddress tryAddress = new InetSocketAddress(port);
        myServerSocketChannel.socket().bind(tryAddress);
        bindPort = tryAddress.getPort();
        break;
      } catch (IOException e) {
        //try next port
        logger.debug("Could not bind to port {}, trying next port...", port);
      }
    }
    if (bindPort == -1) {
      logger.error(String.format(
              "No available ports in range [%d, %d] for the BitTorrent client!", firstTryPort, lastTryPort
      ));
      throw new IOException("No available port for the BitTorrent client!");
    }
    return myServerSocketChannel;
  }
}
