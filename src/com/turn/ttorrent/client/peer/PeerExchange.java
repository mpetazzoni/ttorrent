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

package com.turn.ttorrent.client.peer;

import com.turn.ttorrent.client.Message;
import com.turn.ttorrent.client.SharedTorrent;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.InterruptedException;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.text.ParseException;
import java.util.BitSet;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Incoming and outgoing peer communication system.
 *
 * The peer exchange is a wrapper around peer communication. It provides both
 * incoming and outgoing communication channels to a connected peer after a
 * successful handshake.
 *
 * When a socket is bound to a sharing peer, a PeerExchange is automatically
 * created to wrap this socket into a more usable system for communication with
 * the remote peer.
 *
 * For incoming messages, the peer exchange provides message parsing and calls
 * the <code>handleMessage()</code> method of the peer for each successfully
 * parsed message.
 *
 * For outgoing message, the peer exchange offers a <code>send()</code> message
 * that queues messages, and takes care of automatically sending a keep-alive
 * message to the remote peer every two minutes when other message have been
 * sent in that period of time, as recommended by the BitTorrent protocol
 * specification.
 *
 * @author mpetazzoni
 */
class PeerExchange {

	private static final Logger logger =
		LoggerFactory.getLogger(PeerExchange.class);

	private static final int KEEP_ALIVE_IDLE_MINUTES = 2;
	private static final int KEEP_ALIVE_FOR_MINUTES = 3;

	private SharingPeer peer;
	private SharedTorrent torrent;
	private Socket socket;

	private Set<MessageListener> listeners;

	private IncomingThread in;
	private OutgoingThread out;
	private BlockingQueue<Message> sendQueue;
	private boolean stop;

	/** Initialize and start a new peer exchange.
	 *
	 * @param peer The remote peer to communicate with.
	 * @param torrent The torrent we're exchanging on with the peer.
	 * @param socket The connected socket to the peer.
	 */
	public PeerExchange(SharingPeer peer, SharedTorrent torrent,
			Socket socket) throws SocketException {
		this.peer = peer;
		this.torrent = torrent;
		this.socket = socket;

		// Set the socket read timeout.
		this.socket.setSoTimeout(PeerExchange.KEEP_ALIVE_FOR_MINUTES*60*1000);

		this.listeners = new HashSet<MessageListener>();
		this.sendQueue = new LinkedBlockingQueue<Message>();

		if (!this.peer.hasPeerId()) {
			throw new IllegalStateException("Peer does not have a " +
					"peer ID. Was the handshake made properly?");
		}

		String peerId = this.peer.getHexPeerId().substring(
				this.peer.getHexPeerId().length()-6)
				.toUpperCase();
		this.in = new IncomingThread();
		this.in.setName("bt-peer(.." + peerId + ")-recv");

		this.out = new OutgoingThread();
		this.out.setName("bt-peer(.." + peerId + ")-send");

		// Automatically start the exchange activity loops
		this.stop = false;
		this.in.start();
		this.out.start();

		logger.debug("Started peer exchange with {} for {}.",
			this.peer, this.torrent);

		// If we have pieces, start by sending a BITFIELD message to the peer.
		BitSet pieces = this.torrent.getCompletedPieces();
		if (pieces.cardinality() > 0) {
			this.send(Message.BitfieldMessage.craft(pieces));
		}
	}

	/** Register a new message listener to receive messages.
	 *
	 * @param listener The message listener object.
	 */
	public void register(MessageListener listener) {
		this.listeners.add(listener);
	}

	/** Tells if the peer exchange is active.
	 */
	public boolean isConnected() {
		return this.socket.isConnected();
	}

	/** Send a message to the connected peer.
	 *
	 * The message is queued in the outgoing message queue and will be
	 * processed as soon as possible.
	 *
	 * @param message The message object to send.
	 */
	public void send(Message message) {
		try {
			this.sendQueue.put(message);
		} catch (InterruptedException ie) {
			// Ignore, our send queue will only block if it contains
			// MAX_INTEGER messages, in which case we're already in big
			// trouble, and we'd have to be interrupted, too.
		}
	}

	/** Close and stop the peer exchange.
	 *
	 * Closes the socket and stops both incoming and outgoing threads.
	 */
	public void close() {
		this.stop = true;

		if (!this.socket.isClosed()) {
			try {
				// Interrupt the incoming thread immediately
				this.in.interrupt();

				// But join the outgoing thread to let it finish serving the queue.
				this.out.join();

				// Finally, close the socket
				this.socket.close();
			} catch (InterruptedException ie) {
				// Ignore
			} catch (IOException ioe) {
				// Ignore
			}
		}

		logger.debug("Peer exchange with {} closed.", this.peer);
	}

	public void terminate() {
		this.stop = true;

		try {
			this.socket.close();
			this.in.interrupt();
			this.out.interrupt();
		} catch (IOException ioe) {
			// Ignore
		}

		logger.debug("Terminated peer exchange with {}.", this.peer);
	}

	/** Incoming messages thread.
	 *
	 * The incoming messages thread reads from the socket's input stream and
	 * waits for incoming messages. When a message is fully retrieve, it is
	 * parsed and passed to the peer's <code>handleMessage()</code> method that
	 * will act based on the message type.
	 */
	private class IncomingThread extends Thread {

		@Override
		public void run() {
			try {
				DataInputStream is = new DataInputStream(
						socket.getInputStream());

				while (!stop) {
					// Read the first byte from the wire to get the message
					// length, then read the specified amount of bytes to get
					// the full message.
					int len = is.readInt();
					ByteBuffer buffer = ByteBuffer.allocate(4 + len);
					buffer.putInt(len);
					is.readFully(buffer.array(), 4, buffer.remaining());

					try {
						Message message = Message.parse(buffer, torrent);
						logger.trace("Received {} from {}", message, peer);

						for (MessageListener listener : listeners) {
							listener.handleMessage(message);
						}
					} catch (ParseException pe) {
						logger.warn("{}", pe.getMessage());
					}
				}
			} catch (IOException ioe) {
				logger.trace("Could not read message from {}: {}",
					new Object[] { peer, ioe.getMessage(), ioe });
				peer.unbind(true);
			}
		}
	}

	/** Outgoing messages thread.
	 *
	 * The outgoing messages thread waits for messages to appear in the send
	 * queue and processes them, in order, as soon as they arrive.
	 *
	 * If no message is available for KEEP_ALIVE_IDLE_MINUTES minutes, it will
	 * automatically send a keep-alive message to the remote peer to keep teh
	 * connection active.
	 */
	private class OutgoingThread extends Thread {

		@Override
		public void run() {
			try {
				OutputStream os = socket.getOutputStream();

				// Loop until told to stop. When stop was requested, loop until
				// the queue is served.
				while (!stop || (stop && sendQueue.size() > 0)) {
					try {
						// Wait for two minutes for a message to send
						Message message = sendQueue.poll(
								PeerExchange.KEEP_ALIVE_IDLE_MINUTES,
								TimeUnit.MINUTES);

						if (message == null) {
							if (stop) {
								return;
							}

							message = Message.KeepAliveMessage.craft();
						}

						logger.trace("Sending {} to {}.", message, peer);
						os.write(message.getData().array());
					} catch (InterruptedException ie) {
						// Ignore and potentially terminate
					}
				}
			} catch (IOException ioe) {
				logger.trace("Could not send message to {}: {}",
					new Object[] { peer, ioe.getMessage(), ioe });
				peer.unbind(true);
			}
		}
	}
}
