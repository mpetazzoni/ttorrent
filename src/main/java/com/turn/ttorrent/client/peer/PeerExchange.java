/**
 * Copyright (C) 2011-2012 Turn, Inc.
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

import com.turn.ttorrent.client.SharedTorrent;
import com.turn.ttorrent.common.protocol.PeerMessage;

import java.io.EOFException;
import java.io.IOException;
import java.lang.InterruptedException;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.text.ParseException;
import java.util.BitSet;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Incoming and outgoing peer communication system.
 *
 * <p>
 * The peer exchange is a wrapper around peer communication. It provides both
 * incoming and outgoing communication channels to a connected peer after a
 * successful handshake.
 * </p>
 *
 * <p>
 * When a socket is bound to a sharing peer, a PeerExchange is automatically
 * created to wrap this socket into a more usable system for communication with
 * the remote peer.
 * </p>
 *
 * <p>
 * For incoming messages, the peer exchange provides message parsing and calls
 * the <code>handleMessage()</code> method of the peer for each successfully
 * parsed message.
 * </p>
 *
 * <p>
 * For outgoing message, the peer exchange offers a <code>send()</code> message
 * that queues messages, and takes care of automatically sending a keep-alive
 * message to the remote peer every two minutes when other message have been
 * sent in that period of time, as recommended by the BitTorrent protocol
 * specification.
 * </p>
 *
 * @author mpetazzoni
 */
class PeerExchange {

	private static final Logger logger =
		LoggerFactory.getLogger(PeerExchange.class);

	private static final int KEEP_ALIVE_IDLE_MINUTES = 2;

	private SharingPeer peer;
	private SharedTorrent torrent;
	private SocketChannel channel;

	private Set<MessageListener> listeners;

	private IncomingThread in;
	private OutgoingThread out;
	private BlockingQueue<PeerMessage> sendQueue;
	private volatile boolean stop;

	/**
	 * Initialize and start a new peer exchange.
	 *
	 * @param peer The remote peer to communicate with.
	 * @param torrent The torrent we're exchanging on with the peer.
	 * @param channel A channel on the connected socket to the peer.
	 */
	public PeerExchange(SharingPeer peer, SharedTorrent torrent,
			SocketChannel channel) throws SocketException {
		this.peer = peer;
		this.torrent = torrent;
		this.channel = channel;

		this.listeners = new HashSet<MessageListener>();
		this.sendQueue = new LinkedBlockingQueue<PeerMessage>();

		if (!this.peer.hasPeerId()) {
			throw new IllegalStateException("Peer does not have a " +
					"peer ID. Was the handshake made properly?");
		}

		this.in = new IncomingThread();
		this.in.setName("bt-peer(" +
			this.peer.getShortHexPeerId() + ")-recv");

		this.out = new OutgoingThread();
		this.out.setName("bt-peer(" +
			this.peer.getShortHexPeerId() + ")-send");
		this.out.setDaemon(true);

		// Automatically start the exchange activity loops
		this.stop = false;
		this.in.start();
		this.out.start();

		logger.debug("Started peer exchange with {} for {}.",
			this.peer, this.torrent);

		// If we have pieces, start by sending a BITFIELD message to the peer.
		BitSet pieces = this.torrent.getCompletedPieces();
		if (pieces.cardinality() > 0) {
			this.send(PeerMessage.BitfieldMessage.craft(pieces));
		}
	}

	/**
	 * Register a new message listener to receive messages.
	 *
	 * @param listener The message listener object.
	 */
	public void register(MessageListener listener) {
		this.listeners.add(listener);
	}

	/**
	 * Tells if the peer exchange is active.
	 */
	public boolean isConnected() {
		return this.channel.isConnected();
	}

	/**
	 * Send a message to the connected peer.
	 *
	 * <p>
	 * The message is queued in the outgoing message queue and will be
	 * processed as soon as possible.
	 * </p>
	 *
	 * @param message The message object to send.
	 */
	public void send(PeerMessage message) {
		try {
			this.sendQueue.put(message);
		} catch (InterruptedException ie) {
			// Ignore, our send queue will only block if it contains
			// MAX_INTEGER messages, in which case we're already in big
			// trouble, and we'd have to be interrupted, too.
		}
	}

	/**
	 * Close and stop the peer exchange.
	 *
	 * <p>
	 * Closes the socket channel and stops both incoming and outgoing threads.
	 * </p>
	 */
	public void close() {
		this.stop = true;

		if (this.channel.isConnected()) {
			try {
				this.channel.close();
			} catch (IOException ioe) {
				// Ignore
			}
		}

		logger.debug("Peer exchange with {} closed.", this.peer);
	}

	/**
	 * Incoming messages thread.
	 *
	 * <p>
	 * The incoming messages thread reads from the socket's input stream and
	 * waits for incoming messages. When a message is fully retrieve, it is
	 * parsed and passed to the peer's <code>handleMessage()</code> method that
	 * will act based on the message type.
	 * </p>
	 * 
	 * @author mpetazzoni
	 */
	private class IncomingThread extends Thread {

		@Override
		public void run() {
			ByteBuffer buffer = ByteBuffer.allocateDirect(1*1024*1024);

			try {
				while (!stop) {
					buffer.rewind();
					buffer.limit(PeerMessage.MESSAGE_LENGTH_FIELD_SIZE);

					if (channel.read(buffer) < 0) {
						throw new EOFException(
							"Reached end-of-stream while reading size header");
					}

					// Keep reading bytes until the length field has been read
					// entirely.
					if (buffer.hasRemaining()) {
						try {
							Thread.sleep(1);
						} catch (InterruptedException ie) {
							// Ignore and move along.
						}

						continue;
					}

					int pstrlen = buffer.getInt(0);
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
						logger.trace("Received {} from {}", message, peer);

						for (MessageListener listener : listeners) {
							listener.handleMessage(message);
						}
					} catch (ParseException pe) {
						logger.warn("{}", pe.getMessage());
					}
				}
			} catch (IOException ioe) {
				logger.debug("Could not read message from {}: {}",
					peer,
					ioe.getMessage() != null
						? ioe.getMessage()
						: ioe.getClass().getName());
				peer.unbind(true);
			}
		}
	}

	/**
	 * Outgoing messages thread.
	 *
	 * <p>
	 * The outgoing messages thread waits for messages to appear in the send
	 * queue and processes them, in order, as soon as they arrive.
	 * </p>
	 *
	 * <p>
	 * If no message is available for KEEP_ALIVE_IDLE_MINUTES minutes, it will
	 * automatically send a keep-alive message to the remote peer to keep the
	 * connection active.
	 * </p>
	 *
	 * @author mpetazzoni
	 */
	private class OutgoingThread extends Thread {

		@Override
		public void run() {
			try {
				// Loop until told to stop. When stop was requested, loop until
				// the queue is served.
				while (!stop || (stop && sendQueue.size() > 0)) {
					try {
						// Wait for two minutes for a message to send
						PeerMessage message = sendQueue.poll(
								PeerExchange.KEEP_ALIVE_IDLE_MINUTES,
								TimeUnit.MINUTES);

						if (message == null) {
							if (stop) {
								return;
							}

							message = PeerMessage.KeepAliveMessage.craft();
						}

						logger.trace("Sending {} to {}", message, peer);

						ByteBuffer data = message.getData();
						while (!stop && data.hasRemaining()) {
							if (channel.write(data) < 0) {
								throw new EOFException(
									"Reached end of stream while writing");
							}
						}
					} catch (InterruptedException ie) {
						// Ignore and potentially terminate
					}
				}
			} catch (IOException ioe) {
				logger.debug("Could not send message to {}: {}",
					peer,
					ioe.getMessage() != null
						? ioe.getMessage()
						: ioe.getClass().getName());
				peer.unbind(true);
			}
		}
	}
}
