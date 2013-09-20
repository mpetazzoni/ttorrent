package com.turn.ttorrent.client;

import com.turn.ttorrent.common.ConnectionUtils;
import com.turn.ttorrent.common.Peer;
import com.turn.ttorrent.common.Torrent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.text.ParseException;
import java.util.UUID;

/**
 * @author Sergey.Pak
 *         Date: 9/9/13
 *         Time: 7:25 PM
 */
public class SingleAddressConnectionHandler implements Runnable  {

  private static final Logger logger =
          LoggerFactory.getLogger(ConnectionHandler.class);

  public static final int PORT_RANGE_START = 6881;
  public static final int PORT_RANGE_END = 6889;

  private static final int CLIENT_KEEP_ALIVE_MINUTES = 3;


  private ServerSocketChannel myServerSocketChannel;
  private InetSocketAddress myBindAddress;
  private final Peer self;
  private volatile boolean stop=false;
  private final TorrentConnectionListener myConnectionListener;
  private final byte[] myIdBytes;

  private Thread mySelfThread=null;

  public SingleAddressConnectionHandler(final InetAddress bindAddress,
                                        final TorrentConnectionListener connectionListener) throws IOException {
    // Bind to the first available port in the range
    // [PORT_RANGE_START; PORT_RANGE_END].
    myConnectionListener = connectionListener;
    final String id = Client.BITTORRENT_ID_PREFIX + UUID.randomUUID().toString().split("-")[4];
    myIdBytes = id.getBytes(Torrent.BYTE_ENCODING);

    for (int port = PORT_RANGE_START;
         port <= PORT_RANGE_END;
         port++) {
      InetSocketAddress tryAddress = new InetSocketAddress(bindAddress, port);

      try {
        this.myServerSocketChannel = ServerSocketChannel.open();
        this.myServerSocketChannel.socket().bind(tryAddress);
        this.myServerSocketChannel.configureBlocking(false);
        if (!this.myServerSocketChannel.socket().isBound()) {
          myServerSocketChannel.close();
          continue;
        }
        if (!this.myServerSocketChannel.socket().isBound()) {
          this.myServerSocketChannel.socket().bind(tryAddress);
          if (!this.myServerSocketChannel.socket().isBound()) {
            logger.warn("Not bound to the {} from the third attempt!!", tryAddress);
          }
        }
        this.myBindAddress = tryAddress;
        break;
      } catch (IOException ioe) {
        // Ignore, try next port
        logger.warn("Could not bind to {}, trying next port...", tryAddress);
      }
    }

    if (this.myServerSocketChannel == null || !this.myServerSocketChannel.socket().isBound()) {
      throw new IOException("No available port for the BitTorrent client!");
    }
    logger.info("Listening for incoming connections on {}.", this.myBindAddress);

    this.self = new Peer(this.myBindAddress.getAddress().getHostAddress(),
            (short) this.myBindAddress.getPort(),
            ByteBuffer.wrap(id.getBytes(Torrent.BYTE_ENCODING)));

  }

  public Peer getSelf() {
    return self;
  }

  public ServerSocketChannel getServerSocketChannel() {
    return myServerSocketChannel;
  }

  public InetSocketAddress getBindAddress() {
    return myBindAddress;
  }

  /**
   * Start accepting new connections in a background thread.
   */
  public void start() {
    if (this.myServerSocketChannel == null) {
      throw new IllegalStateException(
              "Connection handler cannot be recycled!");
    }

    this.stop = false;

    if (mySelfThread == null || !mySelfThread.isAlive()) {
      mySelfThread = new Thread(this);
      mySelfThread.setName(String.format("bt-serve[%s]", self.toString()));
      mySelfThread.start();
    }

  }


  public void stop(){
    stop = true;

    if (mySelfThread != null && mySelfThread.isAlive()) {
      try {
        mySelfThread.join();
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
      }
    }
    try {
      myServerSocketChannel.close();
    } catch (IOException e) {

    }

    mySelfThread = null;
  }

  /**
   * The main service loop.
   * <p/>
   * <p>
   * The service waits for new connections for 250ms, then waits 100ms so it
   * can be interrupted.
   * </p>
   */
  @Override
  public void run() {

    logger.info("BitTorrent client [{}] started and " +
            "listening at {}:{}...",
            new Object[]{
                    this.self.getShortHexPeerId(),
                    this.self.getIp(),
                    this.self.getPort()
            });

    while (!this.stop) {
      try {
        SocketChannel client = this.myServerSocketChannel.accept();
        if (client != null) {
          int recvBufferSize = 65536;
          try {
            recvBufferSize = Integer.parseInt(System.getProperty("torrent.recv.buffer.size", "65536"));
          } catch (NumberFormatException ne){
          }
          client.socket().setReceiveBufferSize(recvBufferSize);
          int sendBufferSize = 65536;
          try {
            sendBufferSize = Integer.parseInt(System.getProperty("torrent.send.buffer.size", "65536"));
          } catch (NumberFormatException ne){
          }
          client.socket().setSendBufferSize(sendBufferSize);
          // this actually doesn't work in 1.6. Waiting for 1.7
          client.socket().setPerformancePreferences(0, 0, 1);
          this.accept(client);
        }
      } catch (SocketTimeoutException ste) {
        // Ignore and go back to sleep
      } catch (IOException ioe) {
        logger.warn("Unrecoverable error in connection handler", ioe);
        this.stop();
      }

      try {
        Thread.sleep(100);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /**
   * Close this connection handler to release the port it is bound to.
   *
   * @throws IOException If the channel could not be closed.
   */
  public void close() throws IOException {
    if (this.myServerSocketChannel != null) {
      this.myServerSocketChannel.close();
      this.myServerSocketChannel = null;
    }
  }

  /**
   * Accept the next incoming connection.
   * <p/>
   * <p>
   * When a new peer connects to this service, wait for it to send its
   * handshake. We then parse and check that the handshake advertises the
   * torrent hash we expect, then reply with our own handshake.
   * </p>
   * <p/>
   * <p>
   * If everything goes according to plan, notify the
   * <code>CommunicationListener</code>s with the connected socket and
   * the parsed peer ID.
   * </p>
   */
  private void accept(SocketChannel clientChannel)
          throws IOException {
    final String socketRepresentation = ConnectionUtils.socketRepr(clientChannel);
    try {
      logger.debug("New incoming connection from {}, waiting for handshake...", socketRepresentation);
      Handshake hs = ConnectionUtils.validateHandshake(clientChannel, null);

      if (!myConnectionListener.hasTorrent(hs)) {
        throw new ParseException("Handshake for unknown torrent " +
                Torrent.byteArrayToHexString(hs.getInfoHash()) +
                " from " + ConnectionUtils.socketRepr(clientChannel) + ".", hs.getPstrlen() + 9);
      }

      logger.trace("Validated handshake from {}. Identifier: {}", socketRepresentation, hs.getTorrentIdentifier());
      int sentBytes = ConnectionUtils.sendHandshake(clientChannel, hs.getInfoHash(), myIdBytes);
      logger.trace("Replied to {} with handshake ({} bytes).", socketRepresentation, sentBytes);

      // Go to non-blocking mode for peer interaction
      clientChannel.configureBlocking(false);
      clientChannel.socket().setSoTimeout(CLIENT_KEEP_ALIVE_MINUTES * 60 * 1000);
      myConnectionListener.handleNewPeerConnection(clientChannel, hs.getPeerId(), hs.getHexInfoHash());
    } catch (ParseException pe) {
      logger.info("Invalid handshake from {}: {}", socketRepresentation, pe.getMessage());
      logger.error(pe.getMessage(), pe);
      try {
        clientChannel.close();
      } catch (IOException e) {
      }
    } catch (IOException ioe) {
      logger.info("An error occured while reading an incoming " +
              "handshake: {}", ioe.getMessage());
      try {
        if (clientChannel.isConnected()) {
          clientChannel.close();
        }
      } catch (IOException e) {
        // Ignore
      }
    }
  }

}
