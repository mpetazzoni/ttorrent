package com.turn.ttorrent.common;

import com.turn.ttorrent.client.CommunicationListener;
import com.turn.ttorrent.client.Handshake;
import com.turn.ttorrent.client.peer.MessageListener;
import com.turn.ttorrent.client.peer.SharingPeerInfo;
import com.turn.ttorrent.common.protocol.PeerMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.SocketChannel;
import java.text.ParseException;
import java.util.*;

/**
 * Contains handy methods that establish various connections
 *
 * @author Sergey.Pak
 *         Date: 8/9/13
 *         Time: 7:09 PM
 */
public class ConnectionUtils {

  private static final Logger logger = LoggerFactory.getLogger(ConnectionUtils.class);

  private static final int MAX_CONNECTION_ATTEMPTS = 5;


  /**
   * Validate an expected handshake on a connection.
   * <p/>
   * <p>
   * Reads an expected handshake message from the given connected socket,
   * parses it and validates that the torrent hash_info corresponds to the
   * torrent we're sharing, and that the peerId matches the peer ID we expect
   * to see coming from the remote peer.
   * </p>
   *
   * @param channel The connected socket channel to the remote peer.
   * @param peerId The peer ID we expect in the handshake. If <em>null</em>,
   *               any peer ID is accepted (this is the case for incoming connections).
   * @return The validated handshake message object.
   */
  public static Handshake validateHandshake(SocketChannel channel, byte[] peerId)
          throws IOException, ParseException {
    ByteBuffer len = ByteBuffer.allocate(1);
    ByteBuffer data;

    // Read the handshake from the wire
    final int readBytes = channel.read(len);
    if (readBytes < 1) {
      throw new IOException("Handshake size read underrrun");
    }

    len.rewind();
    int pstrlen = len.get();

    data = ByteBuffer.allocate(Handshake.BASE_HANDSHAKE_LENGTH + pstrlen);
    data.put((byte) pstrlen);
    int expected = data.remaining();
    int read = channel.read(data);
    if (read < expected) {
      throw new IOException("Handshake data read underrun (" +
              read + " < " + expected + " bytes)");
    }

    // Parse and check the handshake
    data.rewind();
    Handshake hs = Handshake.parse(data, pstrlen);

    if (peerId != null && !Arrays.equals(hs.getPeerId(), peerId)) {
      throw new ParseException(
              String.format("Announced peer ID %s did not match expected peer ID %s.",
                      Torrent.byteArrayToHexString(hs.getPeerId()),
                      Torrent.byteArrayToHexString(peerId)),
              hs.getPstrlen() + 29);
    }

    return hs;
  }

  /**
   * Send our handshake message to the socket.
   *
   * @param channel The socket myServerSocketChannel to the remote peer.
   */
  public static int sendHandshake(ByteChannel channel, byte[] infoHash, byte[] peerId) throws IOException {
    final Handshake craft = Handshake.craft(infoHash,peerId);
    return channel.write(craft.getData());
  }

  public static SocketChannel connect(Peer peerInfo, Map<InetAddress, byte[]> selfIdCandidates, TorrentHash torrentHash, Collection<CommunicationListener> listeners){
    InetSocketAddress address =
            new InetSocketAddress(peerInfo.getIp(), peerInfo.getPort());
    SocketChannel channel = null;

    try {
      logger.debug("Connecting to {}...", peerInfo);
      channel = SocketChannel.open(address);
      int connectionAttempts = 0;
      while (!channel.isConnected() && ++connectionAttempts <= MAX_CONNECTION_ATTEMPTS) {
        Thread.sleep(50);
      }
       if (!channel.isConnected()){
         throw new RuntimeException(String.format("Connect to %s timed out", peerInfo.getAddress()));
       }

      logger.trace("Connected. Sending handshake to {}...", peerInfo);
      channel.configureBlocking(true);
      byte[] selfPeerId = new byte[20];
      Iterator<Map.Entry<InetAddress, byte[]>> iterator = selfIdCandidates.entrySet().iterator();
      if (iterator.hasNext()) {
        selfPeerId = iterator.next().getValue();
      }
      int sent = sendHandshake(channel, torrentHash.getInfoHash(), selfPeerId);
      logger.trace("Sent handshake ({} bytes), waiting for response...", sent);
      Handshake hs = validateHandshake(channel,peerInfo.getPeerIdArray());
      logger.debug("Handshaked with {}, peer ID is {}.",
              peerInfo, Torrent.byteArrayToHexString(hs.getPeerId()));

      // Go to non-blocking mode for peer interaction
      channel.configureBlocking(false);
      for (CommunicationListener listener : listeners) {
        listener.handleNewPeerConnection(channel, hs.getPeerId(), torrentHash.getHexInfoHash());
      }

    } catch (Exception e) {
      logger.debug("Connection error", e);
      try {
        if (channel != null && channel.isConnected()) {
          channel.close();
        }
      } catch (IOException ioe) {
        // Ignore
      }
      for (CommunicationListener listener : listeners) {
        listener.handleFailedConnection(peerInfo, e);
      }
    }
    return channel;
  }

  /**
   * Return a human-readable representation of a connected socket myServerSocketChannel.
   *
   * @param channel The socket myServerSocketChannel to represent.
   * @return A textual representation (<em>host:port</em>) of the given
   *         socket.
   */
  public static String socketRepr(SocketChannel channel) {
    Socket s = channel.socket();
    return String.format("%s:%d%s",
            s.getInetAddress().getHostName(),
            s.getPort(),
            channel.isConnected() ? "+" : "-");
  }

  public static boolean readAndHandleMessage(ByteBuffer buffer, ByteChannel channel, boolean stop, TorrentInfo torrent,
                                             Collection<MessageListener> listeners) throws IOException {
    buffer.rewind();
    buffer.limit(PeerMessage.MESSAGE_LENGTH_FIELD_SIZE);

    final int read = channel.read(buffer);
    if (read < 0) {
      throw new EOFException(
              "Reached end-of-stream while reading size header");
    }

    if (read==0){

      return true;
    }

    // Keep reading bytes until the length field has been read
    // entirely.
    if (buffer.hasRemaining()) {
      return true;
    }

    int pstrlen = buffer.getInt(0);
    if (PeerMessage.MESSAGE_LENGTH_FIELD_SIZE + pstrlen > buffer.capacity()){
      logger.debug("Proposed limit of {} is larger than capacity of {}",
              PeerMessage.MESSAGE_LENGTH_FIELD_SIZE + pstrlen, buffer.capacity() );
    }
    buffer.limit(PeerMessage.MESSAGE_LENGTH_FIELD_SIZE + pstrlen);

    while (!stop && buffer.hasRemaining()) {
      if (channel.read(buffer) < 0) {
        throw new EOFException(
                "Reached end-of-stream while reading message");
      }
    }

    buffer.rewind();

    try {
      PeerMessage message = PeerMessage.parse(buffer, torrent);
      for (MessageListener listener : listeners) {
        listener.handleMessage(message);
      }
    } catch (ParseException pe) {
      logger.debug("{}", pe.getMessage());
    }
    return false;
  }


}
