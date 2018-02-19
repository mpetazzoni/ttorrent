/**
 * Copyright (C) 2012 Turn, Inc.
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
package com.turn.ttorrent.common.protocol;

import com.turn.ttorrent.common.Peer;

import java.nio.ByteBuffer;
import java.util.List;


/**
 * BitTorrent tracker protocol messages representations.
 *
 * <p>
 * This class and its <em>*TrackerMessage</em> subclasses provide POJO
 * representations of the tracker protocol messages, for at least HTTP and UDP
 * trackers' protocols, along with easy parsing from an input ByteBuffer to
 * quickly get a usable representation of an incoming message.
 * </p>
 *
 * @author mpetazzoni
 */
public abstract class TrackerMessage {

	/**
	 * Message type.
	 */
	public enum Type {
		UNKNOWN(-1),
		CONNECT_REQUEST(0),
		CONNECT_RESPONSE(0),
		ANNOUNCE_REQUEST(1),
		ANNOUNCE_RESPONSE(1),
		SCRAPE_REQUEST(2),
		SCRAPE_RESPONSE(2),
		ERROR(3);

		private final int id;

		Type(int id) {
			this.id = id;
		}

		public int getId() {
			return this.id;
		}
	};

	private final Type type;
	private final ByteBuffer data;

	/**
	 * Constructor for the base tracker message type.
	 *
	 * @param type The message type.
	 * @param data A byte buffer containing the binary data of the message (a
	 * B-encoded map, a UDP packet data, etc.).
	 */
	protected TrackerMessage(Type type, ByteBuffer data) {
		this.type = type;
		this.data = data;
		if (this.data != null) {
			this.data.rewind();
		}
	}

	/**
	 * Returns the type of this tracker message.
	 */
	public Type getType() {
		return this.type;
	}

	/**
	 * Returns the encoded binary data for this message.
	 */
	public ByteBuffer getData() {
		return this.data;
	}

	/**
	 * Generic exception for message format and message validation exceptions.
	 */
	public static class MessageValidationException extends Exception {

		static final long serialVersionUID = -1;

		public MessageValidationException(String s) {
			super(s);
		}

		public MessageValidationException(String s, Throwable cause) {
			super(s, cause);
		}

	}


	/**
	 * Base interface for connection request messages.
	 *
	 * <p>
	 * This interface must be implemented by all subtypes of connection request
	 * messages for the various tracker protocols.
	 * </p>
	 *
	 * @author mpetazzoni
	 */
	public interface ConnectionRequestMessage {

	};


	/**
	 * Base interface for connection response messages.
	 *
	 * <p>
	 * This interface must be implemented by all subtypes of connection
	 * response messages for the various tracker protocols.
	 * </p>
	 *
	 * @author mpetazzoni
	 */
	public interface ConnectionResponseMessage {

	};


	/**
	 * Base interface for announce request messages.
	 *
	 * <p>
	 * This interface must be implemented by all subtypes of announce request
	 * messages for the various tracker protocols.
	 * </p>
	 *
	 * @author mpetazzoni
	 */
	public interface AnnounceRequestMessage {

		public static final int DEFAULT_NUM_WANT = 50;

		/**
		 * Announce request event types.
		 *
		 * <p>
		 * When the client starts exchanging on a torrent, it must contact the
		 * torrent's tracker with a 'started' announce request, which notifies the
		 * tracker this client now exchanges on this torrent (and thus allows the
		 * tracker to report the existence of this peer to other clients).
		 * </p>
		 *
		 * <p>
		 * When the client stops exchanging, or when its download completes, it must
		 * also send a specific announce request. Otherwise, the client must send an
		 * eventless (NONE), periodic announce request to the tracker at an
		 * interval specified by the tracker itself, allowing the tracker to
		 * refresh this peer's status and acknowledge that it is still there.
		 * </p>
		 */
		public enum RequestEvent {
			NONE(0),
			COMPLETED(1),
			STARTED(2),
			STOPPED(3);

			private final int id;
			RequestEvent(int id) {
				this.id = id;
			}

			public String getEventName() {
				return this.name().toLowerCase();
			}

			public int getId() {
				return this.id;
			}

			public static RequestEvent getByName(String name) {
				for (RequestEvent type : RequestEvent.values()) {
					if (type.name().equalsIgnoreCase(name)) {
						return type;
					}
				}
				return null;
			}

			public static RequestEvent getById(int id) {
				for (RequestEvent type : RequestEvent.values()) {
					if (type.getId() == id) {
						return type;
					}
				}
				return null;
			}
		};

		public byte[] getInfoHash();
		public String getHexInfoHash();
		public byte[] getPeerId();
		public String getHexPeerId();
		public int getPort();
		public long getUploaded();
		public long getDownloaded();
		public long getLeft();
		public boolean getCompact();
		public boolean getNoPeerIds();
		public RequestEvent getEvent();

		public String getIp();
		public int getNumWant();
	};


	/**
	 * Base interface for announce response messages.
	 *
	 * <p>
	 * This interface must be implemented by all subtypes of announce response
	 * messages for the various tracker protocols.
	 * </p>
	 *
	 * @author mpetazzoni
	 */
	public interface AnnounceResponseMessage {

		public int getInterval();
		public int getComplete();
		public int getIncomplete();
		public List<Peer> getPeers();
	};


	/**
	 * Base interface for tracker error messages.
	 *
	 * <p>
	 * This interface must be implemented by all subtypes of tracker error
	 * messages for the various tracker protocols.
	 * </p>
	 *
	 * @author mpetazzoni
	 */
	public interface ErrorMessage {

		/**
		 * The various tracker error states.
		 *
		 * <p>
		 * These errors are reported by the tracker to a client when expected
		 * parameters or conditions are not present while processing an
		 * announce request from a BitTorrent client.
		 * </p>
		 */
		public enum FailureReason {
			UNKNOWN_TORRENT("The requested torrent does not exist on this tracker"),
			MISSING_HASH("Missing info hash"),
			MISSING_PEER_ID("Missing peer ID"),
			MISSING_PORT("Missing port"),
			INVALID_EVENT("Unexpected event for peer state"),
			NOT_IMPLEMENTED("Feature not implemented");

			private String message;

			FailureReason(String message) {
				this.message = message;
			}

			public String getMessage() {
				return this.message;
			}
		};

		public String getReason();
	};
}
