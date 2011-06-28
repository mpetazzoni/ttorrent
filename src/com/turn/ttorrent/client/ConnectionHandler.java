/** Copyright (C) 2011 Turn, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.turn.ttorrent.client;

import com.turn.ttorrent.common.Torrent;
import com.turn.ttorrent.client.peer.SharingPeer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.text.ParseException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.apache.log4j.Logger;

/** Incoming peer connections service.
 *
 * <p>
 * Every BitTorrent client, BitTorrent being a peer-to-peer protocol, listens
 * on a port for incoming connections from other peers sharing the same
 * torrent.
 * </p>
 *
 * <p>
 * This ConnectionHandler implements this service and starts a listening socket
 * in the first available port in the default BitTorrent client port range
 * 6881-6889. When a peer connects to it, it expects the BitTorrent handshake
 * message, parses it and replies with our own handshake.
 * </p>
 *
 * <p>
 * Outgoing connections to other peers are also made through this service,
 * which handles the handshake procedure with the remote peer. Regardless of
 * the direction of the connection, once this handshake is successfull, all
 * {@link IncomingConnectionListener}s are notified and passed the connected
 * socket and the remote peer ID.
 * </p>
 *
 * <p>
 * This class does nothing more. All further peer-to-peer communication happens
 * in the {@link com.turn.ttorrent.client.peer.PeerExchange PeerExchange}
 * class.
 * </p>
 *
 * @author mpetazzoni
 * @see <a href="http://wiki.theory.org/BitTorrentSpecification#Handshake">BitTorrent handshake specification</a>
 */
public class ConnectionHandler implements Runnable {

	private static final Logger logger = Logger.getLogger(ConnectionHandler.class);

	public static final int PORT_RANGE_START = 6881;
	public static final int PORT_RANGE_END = 6889;

	private SharedTorrent torrent;
	private String id;
	private ServerSocket socket;
	private InetSocketAddress address;

	private Set<IncomingConnectionListener> listeners;

	private Thread thread;
	private boolean stop;

	/** Create and start a new listening service for out torrent, reporting
	 * with our peer ID on the given address.
	 *
	 * This binds to the first available port in the client port range
	 * PORT_RANGE_START to PORT_RANGE_END.
	 *
	 * @param torrent The torrent shared by this client.
	 * @param id This client's peer ID.
	 * @param address The address to bind to.
	 * @throws IOException When the service can't be started because no port in
	 * the defined range is available or usable.
	 */
	ConnectionHandler(SharedTorrent torrent, String id, InetAddress address)
		throws IOException {
		this.torrent = torrent;
		this.id = id;

		this.socket = new ServerSocket();

		// Bind to the first available port in the range
		// [PORT_RANGE_START; PORT_RANGE_END].
		for (int port = ConnectionHandler.PORT_RANGE_START;
				port <= ConnectionHandler.PORT_RANGE_END;
				port++) {
			InetSocketAddress tryAddress =
				new InetSocketAddress(address, port);

			try {
				this.socket.bind(tryAddress);
				this.address = tryAddress;
				break;
			} catch (IOException ioe) {
				// Ignore, try next port
				logger.info("Could not bind to " + tryAddress + "!");
			}
		}

		if (!this.socket.isBound()) {
			throw new IOException("No available port for BitTorrent client!");
		}

		this.listeners = new HashSet<IncomingConnectionListener>();
		this.thread = null;
	}

	/** Return the full socket address this service is bound to.
	 */
	public InetSocketAddress getSocketAddress() {
		return this.address;
	}

	/** Register a new incoming connection listener.
	 *
	 * @param listener The listener who wants to receive connection
	 * notifications.
	 */
	public void register(IncomingConnectionListener listener) {
		this.listeners.add(listener);
	}

	/** Start accepting new connections in a background thread.
	 */
	public void start() {
		if (!this.socket.isBound()) {
			throw new IllegalStateException("Can't start ConnectionHandler " +
					"without a bound socket!");
		}

		this.stop = false;

		if (this.thread == null || !this.thread.isAlive()) {
			this.thread = new Thread(this);
			this.thread.setName("bt-serve");
			this.thread.start();
		}
	}

	/** Stop accepting connections.
	 *
	 * <b>Note:</b> the underlying socket remains open and bound.
	 */
	public void stop() {
		this.stop = true;

		if (this.thread != null && this.thread.isAlive()) {
			this.thread.interrupt();
		}

		this.thread = null;
	}

	/** The main service loop.
	 *
	 * The service waits for new connections for 250ms, then waits 750ms so it
	 * can be interrupted.
	 */
	@Override
	public void run() {
		try {
			this.socket.setSoTimeout(250);
		} catch (SocketException se) {
			logger.warn(se);
			this.stop();
		}

		while (!this.stop) {
			try {
				this.accept();
			} catch (SocketTimeoutException ste) {
				// Ignore and go back to sleep
			} catch (IOException ioe) {
				logger.warn(ioe);
				this.stop();
			}

			try {
				Thread.sleep(750);
			} catch (InterruptedException ie) {
				// Ignore
			}
		}

		try {
			this.socket.close();
		} catch (IOException ioe) {
			// Ignore
		}
	}

	/** Return a human-readable representation of a connected socket.
	 *
	 * This returns a <em>host:port</em> string representing the given socket.
	 *
	 * @param s The socket to represent.
	 */
	private String socketRepr(Socket s) {
		return new StringBuilder(s.getInetAddress().getHostName())
			.append(":").append(s.getPort()).toString();
	}

	/** Accept the next incoming connection.
	 *
	 * When a new peer connects to this service, wait for it to send its
	 * handshake. We then parse and check that the handshake advertises the
	 * torrent hash we expect, then reply with our own handshake.
	 *
	 * If everything goes according to plan, notify the
	 * <code>IncomingConnectionListener</code>s with the connected socket and
	 * the parsed peer ID.
	 */
	private void accept() throws IOException, SocketTimeoutException {
		Socket socket = this.socket.accept();

		try {
			logger.debug("New incoming connection ...");
			Handshake hs = this.validateHandshake(socket, null);
			this.sendHandshake(socket);
			this.fireNewPeerConnection(socket, hs.getPeerId());
		} catch (ParseException pe) {
			logger.info("Invalid handshake from " + this.socketRepr(socket) +
					": " + pe.getMessage());
			try { socket.close(); } catch (IOException e) { }
		} catch (IOException ioe) {
			logger.info("An error occured while reading an incoming " +
					"handshake: " + ioe.getMessage());
			try {
				if (!socket.isClosed()) {
					socket.close();
				}
			} catch (IOException e) {
				// Ignore
			}
		}
	}

	/** Connect to the given peer and perform the BitTorrent handshake.
	 *
	 * Connect to the peer using its defined IP address and port, then execute
	 * the handshake process to validate the remote peer Id.
	 *
	 * If everything goes according to plan, notify the
	 * <code>IncomingConnectionListener</code>s with the connected socket and
	 * the parsed peer ID.
	 *
	 * @param peer The peer to connect to.
	 */
	public boolean connect(SharingPeer peer) {
		Socket socket = new Socket();
		InetSocketAddress address = new InetSocketAddress(peer.getIp(),
				peer.getPort());

		logger.info("Connecting to " + peer + "...");
		try {
			socket.connect(address, 3*1000);
		} catch (IOException ioe) {
			// Could not connect to peer, abort
			logger.warn("Could not connect to " + peer + ": " +
					ioe.getMessage());
			return false;
		}

		try {
			this.sendHandshake(socket);
			Handshake hs = this.validateHandshake(socket,
					(peer.hasPeerId() ? peer.getPeerId().array() : null));
			this.fireNewPeerConnection(socket, hs.getPeerId());
			return true;
		} catch (ParseException pe) {
			logger.info("Invalid handshake from " + this.socketRepr(socket) +
					": " + pe.getMessage());
			try { socket.close(); } catch (IOException e) { }
		} catch (IOException ioe) {
			logger.info("An error occured while reading an incoming " +
					"handshake: " + ioe.getMessage());
			try {
				if (!socket.isClosed()) {
					socket.close();
				}
			} catch (IOException e) {
				// Ignore
			}
		}

		return false;
	}

	/** Validate an expected handshake on a connection.
	 *
	 * Reads an expected handshake message from the given connected socket,
	 * parses it and validates that the torrent hash_info corresponds to the
	 * torrent we're sharing, and that the peerId matches the peer ID we expect
	 * to see coming from the remote peer.
	 *
	 * @param socket The connected socket to the remote peer.
	 * @param peerId The peer ID we expect in the handshake. If <em>null</em>,
	 * any peer ID is accepted (this is the case for incoming connections).
	 * @return The validated handshake message object.
	 */
	private Handshake validateHandshake(Socket socket, byte[] peerId)
		throws IOException, ParseException {
		InputStream is = socket.getInputStream();

		// Read the handshake from the wire
		int pstrlen = is.read();
		byte[] data = new byte[Handshake.BASE_HANDSHAKE_LENGTH + pstrlen];
		data[0] = (byte)pstrlen;
		is.read(data, 1, data.length-1);

		// Parse and check the handshake
		Handshake hs = Handshake.parse(ByteBuffer.wrap(data));
		if (!Arrays.equals(hs.getInfoHash(), this.torrent.getInfoHash())) {
			throw new ParseException("Handshake for unknow torrent " +
					Torrent.byteArrayToHexString(hs.getInfoHash()) +
					" from " + this.socketRepr(socket) + ".", pstrlen + 9);
		}

		if (peerId != null && !Arrays.equals(hs.getPeerId(), peerId)) {
			throw new ParseException("Announced peer ID " +
					Torrent.byteArrayToHexString(hs.getPeerId()) +
					" did not match expected peer ID " +
					Torrent.byteArrayToHexString(peerId) + ".", pstrlen + 29);
		}

		return hs;
	}

	/** Send our handshake message to the socket.
	 *
	 * @param socket The socket to the remote peer.
	 */
	private void sendHandshake(Socket socket) throws IOException {
		OutputStream os = socket.getOutputStream();
		os.write(Handshake.craft(this.torrent.getInfoHash(),
					this.id.getBytes(Torrent.BYTE_ENCODING)).getBytes());
	}

	/** Trigger the new peer connection event on all registered listeners.
	 *
	 * @param socket The socket to the newly connected peer.
	 * @param peerId The peer ID of the connected peer.
	 */
	private void fireNewPeerConnection(Socket socket, byte[] peerId) {
		for (IncomingConnectionListener listener : this.listeners) {
			listener.handleNewPeerConnection(socket, peerId);
		}
	}
}
