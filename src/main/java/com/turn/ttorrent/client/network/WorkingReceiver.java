package com.turn.ttorrent.client.network;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.SocketChannel;

public class WorkingReceiver implements DataProcessor {

  private static final Logger logger = LoggerFactory.getLogger(WorkingReceiver.class);

  private final String peerUID;

  public WorkingReceiver(String uid) {
    this.peerUID = uid;
  }

  @Override
  public DataProcessor processAndGetNext(SocketChannel socketChannel) throws IOException {
    logger.trace("received data from " + socketChannel.socket());

    return null;
  }
}
