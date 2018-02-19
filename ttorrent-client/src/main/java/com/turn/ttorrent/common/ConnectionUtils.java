package com.turn.ttorrent.common;

import com.turn.ttorrent.client.Handshake;

import java.io.IOException;
import java.nio.channels.ByteChannel;

/**
 * Contains handy methods that establish various connections
 *
 * @author Sergey.Pak
 *         Date: 8/9/13
 *         Time: 7:09 PM
 */
public class ConnectionUtils {

  /**
   * Send our handshake message to the socket.
   *
   * @param channel The socket myServerSocketChannel to the remote peer.
   */
  public static int sendHandshake(ByteChannel channel, byte[] infoHash, byte[] peerId) throws IOException {
    final Handshake craft = Handshake.craft(infoHash,peerId);
    return channel.write(craft.getData());
  }
}
