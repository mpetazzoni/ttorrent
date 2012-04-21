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

import com.turn.ttorrent.bcodec.BDecoder;
import com.turn.ttorrent.bcodec.BEValue;
import com.turn.ttorrent.bcodec.BEncoder;
import com.turn.ttorrent.bcodec.InvalidBEncodingException;
import com.turn.ttorrent.common.Peer;
import com.turn.ttorrent.common.Torrent;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;


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
	private TrackerMessage(Type type, ByteBuffer data) {
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
			NONE,
			STARTED,
			STOPPED,
			COMPLETED;

			public String getEventName() {
				return this.name().toLowerCase();
			}

			public static RequestEvent get(String event) {
				for (RequestEvent type : RequestEvent.values()) {
					if (type.name().equalsIgnoreCase(event)) {
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
		public String getKey();
		public String getTrackerId();
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
		public int getMinInterval();
		public String getTrackerId();
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


	/**
	 * Base class for HTTP tracker messages.
	 *
	 * @author mpetazzoni
	 */
	public static abstract class HTTPTrackerMessage extends TrackerMessage {

		private HTTPTrackerMessage(Type type, ByteBuffer data) {
			super(type, data);
		}

		public static HTTPTrackerMessage parse(ByteBuffer data)
			throws IOException, MessageValidationException {
			Map<String, BEValue> params = BDecoder.bdecode(data).getMap();

			if (params.containsKey("info_hash")) {
				return HTTPAnnounceRequestMessage.parse(data);
			} else if (params.containsKey("peers")) {
				return HTTPAnnounceResponseMessage.parse(data);
			} else if (params.containsKey("failure reason")) {
				return HTTPTrackerErrorMessage.parse(data);
			}

			throw new MessageValidationException("Unknown HTTP tracker message!");
		}
	};


	/**
	 * The announce request message for the HTTP tracker protocol.
	 *
	 * <p>
	 * This class represents the announce request message in the HTTP tracker
	 * protocol. It doesn't add any specific fields compared to the generic
	 * announce request message, but it provides the means to parse such
	 * messages and craft them.
	 * </p>
	 *
	 * @author mpetazzoni
	 */
	public static class HTTPAnnounceRequestMessage extends HTTPTrackerMessage
		implements AnnounceRequestMessage {

		private final byte[] infoHash;
		private final Peer peer;
		private final long uploaded;
		private final long downloaded;
		private final long left;
		private final boolean compact;
		private final boolean noPeerId;
		private final RequestEvent event;
		private final int numWant;
		private final String key;
		private final String trackerId;

		private HTTPAnnounceRequestMessage(ByteBuffer data,
			byte[] infoHash, Peer peer, long uploaded, long downloaded,
			long left, boolean compact, boolean noPeerId, RequestEvent event,
			int numWant, String key, String trackerId) {
			super(Type.ANNOUNCE_REQUEST, data);
			this.infoHash = infoHash;
			this.peer = peer;
			this.downloaded = downloaded;
			this.uploaded = uploaded;
			this.left = left;
			this.compact = compact;
			this.noPeerId = noPeerId;
			this.event = event;
			this.numWant = numWant;
			this.key = key;
			this.trackerId = trackerId;
		}

		@Override
		public byte[] getInfoHash() {
			return this.infoHash;
		}

		@Override
		public String getHexInfoHash() {
			return Torrent.byteArrayToHexString(this.infoHash);
		}

		@Override
		public byte[] getPeerId() {
			return this.peer.getPeerId().array();
		}

		@Override
		public String getHexPeerId() {
			return this.peer.getHexPeerId();
		}

		@Override
		public int getPort() {
			return this.peer.getPort();
		}

		@Override
		public long getUploaded() {
			return this.uploaded;
		}

		@Override
		public long getDownloaded() {
			return this.downloaded;
		}

		@Override
		public long getLeft() {
			return this.left;
		}

		@Override
		public boolean getCompact() {
			return this.compact;
		}

		@Override
		public boolean getNoPeerIds() {
			return this.noPeerId;
		}

		@Override
		public RequestEvent getEvent() {
			return this.event;
		}

		@Override
		public String getIp() {
			return this.peer.getIp();
		}

		@Override
		public int getNumWant() {
			return this.numWant;
		}

		@Override
		public String getKey() {
			return this.key;
		}

		@Override
		public String getTrackerId() {
			return this.trackerId;
		}

		/**
		 * Build the announce request URL for the given tracker announce URL.
		 *
		 * @param trackerAnnounceURL The tracker's announce URL.
		 * @return The URL object representing the announce request URL.
		 */
		public URL buildAnnounceURL(URL trackerAnnounceURL)
			throws UnsupportedEncodingException, MalformedURLException {
			String base = trackerAnnounceURL.toString();
			StringBuilder url = new StringBuilder(base);
			url.append(base.contains("?") ? "&" : "?")
				.append("info_hash=")
				.append(URLEncoder.encode(
					new String(this.getInfoHash(), Torrent.BYTE_ENCODING),
					Torrent.BYTE_ENCODING))
				.append("&peer_id=")
				.append(URLEncoder.encode(
					new String(this.getPeerId(), Torrent.BYTE_ENCODING),
					Torrent.BYTE_ENCODING))
				.append("&port=").append(this.getPort())
				.append("&uploaded=").append(this.getUploaded())
				.append("&downloaded=").append(this.getDownloaded())
				.append("&left=").append(this.getLeft())
				.append("&compact=").append(this.getCompact() ? 1 : 0)
				.append("&no_peer_id=").append(this.getNoPeerIds() ? 1 : 0);

			if (this.getEvent() != null &&
				!RequestEvent.NONE.equals(this.getEvent())) {
				url.append("&event=").append(this.getEvent().getEventName());
			}

			if (this.getIp() != null) {
				url.append("&ip=").append(this.getIp());
			}

			if (this.getKey() != null) {
				url.append("&key=").append(this.getKey());
			}

			if (this.getTrackerId() != null) {
				url.append("&trackerid=").append(this.getTrackerId());
			}

			return new URL(url.toString());
		}

		public static HTTPAnnounceRequestMessage parse(ByteBuffer data)
			throws IOException, MessageValidationException {
			Map<String, BEValue> params = BDecoder.bdecode(data).getMap();

			if (!params.containsKey("info_hash")) {
				throw new MessageValidationException(
					ErrorMessage.FailureReason.MISSING_HASH.getMessage());
			}

			if (!params.containsKey("peer_id")) {
				throw new MessageValidationException(
					ErrorMessage.FailureReason.MISSING_PEER_ID.getMessage());
			}

			if (!params.containsKey("port")) {
				throw new MessageValidationException(
					ErrorMessage.FailureReason.MISSING_PORT.getMessage());
			}

			try {
				byte[] infoHash = params.get("info_hash").getBytes();
				byte[] peerId = params.get("peer_id").getBytes();
				int port = params.get("port").getInt();

				// Default 'uploaded' and 'downloaded' to 0 if the client does
				// not provide it (although it should, according to the spec).
				long uploaded = 0;
				if (params.containsKey("uploaded")) {
					uploaded = params.get("uploaded").getLong();
				}

				long downloaded = 0;
				if (params.containsKey("downloaded")) {
					downloaded = params.get("downloaded").getLong();
				}

				// Default 'left' to -1 to avoid peers entering the COMPLETED
				// state when they don't provide the 'left' parameter.
				long left = -1;
				if (params.containsKey("left")) {
					left = params.get("left").getLong();
				}

				boolean compact = false;
				if (params.containsKey("compact")) {
					compact = params.get("compact").getInt() == 1;
				}

				boolean noPeerId = false;
				if (params.containsKey("no_peer_id")) {
					noPeerId = params.get("no_peer_id").getInt() == 1;
				}

				int numWant = AnnounceRequestMessage.DEFAULT_NUM_WANT;
				if (params.containsKey("numwant")) {
					numWant = params.get("numwant").getInt();
				}

				String ip = null;
				if (params.containsKey("ip")) {
					ip = params.get("ip").getString(Torrent.BYTE_ENCODING);
				}

				RequestEvent event = RequestEvent.NONE;
				if (params.containsKey("event")) {
					event = RequestEvent.get(params.get("event")
						.getString(Torrent.BYTE_ENCODING));
				}

				String key = null;
				if (params.containsKey("key")) {
					key = params.get("key").getString(Torrent.BYTE_ENCODING);
				}

				String trackerId = null;
				if (params.containsKey("trackerid")) {
					trackerId = params.get("trackerid")
						.getString(Torrent.BYTE_ENCODING);
				}

				return new HTTPAnnounceRequestMessage(data, infoHash,
					new Peer(ip, port, ByteBuffer.wrap(peerId)),
					downloaded, uploaded, left, compact, noPeerId,
					event, numWant, key, trackerId);
			} catch (InvalidBEncodingException ibee) {
				throw new MessageValidationException(
					"Invalid HTTP tracker request!", ibee);
			}
		}

		public static HTTPAnnounceRequestMessage craft(byte[] infoHash,
			byte[] peerId, int port, long uploaded, long downloaded, long left,
			boolean compact, boolean noPeerId, RequestEvent event,
			String ip, int numWant, String key, String trackerId)
			throws IOException, MessageValidationException,
				UnsupportedEncodingException {
			Map<String, BEValue> params = new HashMap<String, BEValue>();
			params.put("info_hash", new BEValue(infoHash));
			params.put("peer_id", new BEValue(peerId));
			params.put("port", new BEValue(port));
			params.put("uploaded", new BEValue(uploaded));
			params.put("downloaded", new BEValue(downloaded));
			params.put("left", new BEValue(left));
			params.put("compact", new BEValue(compact ? 1 : 0));
			params.put("no_peer_id", new BEValue(noPeerId ? 1 : 0));

			if (event != null) {
				params.put("event",
					new BEValue(event.getEventName(), Torrent.BYTE_ENCODING));
			}

			if (ip != null) {
				params.put("ip",
					new BEValue(ip, Torrent.BYTE_ENCODING));
			}

			if (numWant != AnnounceRequestMessage.DEFAULT_NUM_WANT) {
				params.put("numwant", new BEValue(numWant));
			}

			if (key != null) {
				params.put("key",
					new BEValue(key, Torrent.BYTE_ENCODING));
			}

			if (trackerId != null) {
				params.put("trackerid",
					new BEValue(trackerId, Torrent.BYTE_ENCODING));
			}

			return new HTTPAnnounceRequestMessage(
				BEncoder.bencode(params),
				infoHash, new Peer(ip, port, ByteBuffer.wrap(peerId)),
				uploaded, downloaded, left, compact, noPeerId, event,
				numWant, key, trackerId);
		}
	};


	/**
	 * The announce response message from an HTTP tracker.
	 *
	 * @author mpetazzoni
	 */
	public static class HTTPAnnounceResponseMessage extends HTTPTrackerMessage
		implements AnnounceResponseMessage {

		private final int interval;
		private final int minInterval;
		private final String trackerId;
		private final int complete;
		private final int incomplete;
		private final List<Peer> peers;

		private HTTPAnnounceResponseMessage(ByteBuffer data,
			int interval, int minInterval, String trackerId, int complete,
			int incomplete, List<Peer> peers) {
			super(Type.ANNOUNCE_RESPONSE, data);
			this.interval = interval;
			this.minInterval = minInterval;
			this.trackerId = trackerId;
			this.complete = complete;
			this.incomplete = incomplete;
			this.peers = peers;
		}

		@Override
		public int getInterval() {
			return this.interval;
		}

		@Override
		public int getMinInterval() {
			return this.minInterval;
		}

		@Override
		public String getTrackerId() {
			return this.trackerId;
		}

		@Override
		public int getComplete() {
			return this.complete;
		}

		@Override
		public int getIncomplete() {
			return this.incomplete;
		}

		@Override
		public List<Peer> getPeers() {
			return this.peers;
		}

		public static HTTPAnnounceResponseMessage parse(ByteBuffer data)
			throws IOException, MessageValidationException {
			Map<String, BEValue> params = BDecoder.bdecode(data).getMap();
			try {
				List<Peer> peers;

				try {
					// First attempt to decode a compact response, since we asked
					// for it.
					peers = toPeerList(params.get("peers").getBytes());
				} catch (InvalidBEncodingException ibee) {
					// Fall back to peer list, non-compact response, in case the
					// tracker did not support compact responses.
					peers = toPeerList(params.get("peers").getList());
				}

				return new HTTPAnnounceResponseMessage(data,
					params.get("interval").getInt(),
					params.containsKey("min interval")
						? params.get("min interval").getInt()
						: -1,
					params.containsKey("tracker id")
						? params.get("tracker id")
							.getString(Torrent.BYTE_ENCODING)
						: null,
					params.get("complete").getInt(),
					params.get("incomplete").getInt(),
					peers);
			} catch (InvalidBEncodingException ibee) {
				throw new MessageValidationException("Invalid response " +
					"from tracker!", ibee);
			} catch (UnknownHostException uhe) {
				throw new MessageValidationException("Invalid peer " +
					"in tracker response!", uhe);
			}
		}

		/**
		 * Build a peer list as a list of {@link Peer}s from the
		 * announce response's peer list (in non-compact mode).
		 *
		 * @param peers The list of {@link BEValue}s dictionaries describing the
		 * peers from the announce response.
		 * @return A {@link List} of {@link Peer}s representing the
		 * peers' addresses. Peer IDs are lost, but they are not crucial.
		 */
		private static List<Peer> toPeerList(List<BEValue> peers)
			throws InvalidBEncodingException {
			List<Peer> result = new LinkedList<Peer>();

			for (BEValue peer : peers) {
				Map<String, BEValue> peerInfo = peer.getMap();
				result.add(new Peer(
					peerInfo.get("ip").getString(Torrent.BYTE_ENCODING),
					peerInfo.get("port").getInt()));
			}

			return result;
		}

		/**
		 * Build a peer list as a list of {@link Peer}s from the
		 * announce response's binary compact peer list.
		 *
		 * @param data The bytes representing the compact peer list from the
		 * announce response.
		 * @return A {@link List} of {@link Peer}s representing the
		 * peers' addresses. Peer IDs are lost, but they are not crucial.
		 */
		private static List<Peer> toPeerList(byte[] data)
			throws InvalidBEncodingException, UnknownHostException {
			int nPeers = data.length / 6;
			if (data.length % 6 != 0) {
				throw new InvalidBEncodingException("Invalid peers " +
					"binary information string!");
			}

			List<Peer> result = new LinkedList<Peer>();
			ByteBuffer peers = ByteBuffer.wrap(data);

			for (int i=0; i < nPeers ; i++) {
				byte[] ipBytes = new byte[4];
				peers.get(ipBytes);
				InetAddress ip = InetAddress.getByAddress(ipBytes);
				int port =
					(0xFF & (int)peers.get()) << 8 |
					(0xFF & (int)peers.get());
				result.add(new Peer(ip.getHostAddress(), port));
			}

			return result;
		}

		/**
		 * Craft a compact announce response message.
		 *
		 * @param interval
		 * @param minInterval
		 * @param trackerId
		 * @param complete
		 * @param incomplete
		 * @param peers
		 */
		public static HTTPAnnounceResponseMessage craft(int interval,
			int minInterval, String trackerId, int complete, int incomplete,
			List<Peer> peers) throws IOException, UnsupportedEncodingException {
			Map<String, BEValue> response = new HashMap<String, BEValue>();
			response.put("interval", new BEValue(interval));
			response.put("minInterval", new BEValue(minInterval));
			response.put("trackerid",
				new BEValue(trackerId, Torrent.BYTE_ENCODING));
			response.put("complete", new BEValue(complete));
			response.put("incomplete", new BEValue(incomplete));

			ByteBuffer data = ByteBuffer.allocate(peers.size() * 6);
			for (Peer peer : peers) {
				byte[] ip = peer.getRawIp();
				if (ip == null || ip.length != 4) {
					continue;
				}
				data.put(ip);
				data.putShort((short)peer.getPort());
			}
			response.put("peers", new BEValue(data.array()));

			return new HTTPAnnounceResponseMessage(
				BEncoder.bencode(response),
				interval, minInterval, trackerId, complete, incomplete,
				peers);
		}
	};


	/**
	 * An error message from an HTTP tracker.
	 *
	 * @author mpetazzoni
	 */
	public static class HTTPTrackerErrorMessage extends HTTPTrackerMessage
		implements ErrorMessage {

		private final String reason;

		private HTTPTrackerErrorMessage(ByteBuffer data, String reason) {
			super(Type.ERROR, data);
			this.reason = reason;
		}

		@Override
		public String getReason() {
			return this.reason;
		}

		public static HTTPTrackerErrorMessage parse(ByteBuffer data)
			throws IOException, MessageValidationException {
			Map<String, BEValue> params = BDecoder.bdecode(data).getMap();
			try {
				return new HTTPTrackerErrorMessage(
					data,
					params.get("failure reason")
						.getString(Torrent.BYTE_ENCODING));
			} catch (InvalidBEncodingException ibee) {
				throw new MessageValidationException("Invalid tracker error " +
					"message!", ibee);
			}
		}

		public static HTTPTrackerErrorMessage craft(
			ErrorMessage.FailureReason reason) throws IOException,
			   MessageValidationException {
			return HTTPTrackerErrorMessage.craft(reason.getMessage());
		}

		public static HTTPTrackerErrorMessage craft(String reason)
			throws IOException, MessageValidationException {
			Map<String, BEValue> params = new HashMap<String, BEValue>();
			params.put("failure reason",
				new BEValue(reason, Torrent.BYTE_ENCODING));
			return new HTTPTrackerErrorMessage(
				BEncoder.bencode(params),
				reason);
		}
	};
}
