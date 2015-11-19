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
package com.turn.ttorrent.common.protocol.http;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import com.turn.ttorrent.bcodec.BDecoder;
import com.turn.ttorrent.bcodec.BEString;
import com.turn.ttorrent.bcodec.BEValue;
import com.turn.ttorrent.bcodec.BEncoder;
import com.turn.ttorrent.bcodec.InvalidBEncodingException;
import com.turn.ttorrent.common.Peer;
import com.turn.ttorrent.common.Torrent;
import com.turn.ttorrent.common.protocol.TrackerMessage.AnnounceRequestMessage;


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
public class HTTPAnnounceRequestMessage extends HTTPTrackerMessage
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

	private HTTPAnnounceRequestMessage(ByteBuffer data,
		byte[] infoHash, Peer peer, long uploaded, long downloaded,
		long left, boolean compact, boolean noPeerId, RequestEvent event,
		int numWant) {
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

		return new URL(url.toString());
	}

	public static HTTPAnnounceRequestMessage parse(ByteBuffer data)
		throws IOException, MessageValidationException {
		BEValue decoded = BDecoder.bdecode(data);
		if (decoded == null) {
			throw new MessageValidationException(
				"Could not decode tracker message (not B-encoded?)!");
		}

		Map<BEString, BEValue> params = decoded.getMap();

		if (!params.containsKey(BEString.fromString("info_hash"))) {
			throw new MessageValidationException(
				ErrorMessage.FailureReason.MISSING_HASH.getMessage());
		}

		if (!params.containsKey(BEString.fromString("peer_id"))) {
			throw new MessageValidationException(
				ErrorMessage.FailureReason.MISSING_PEER_ID.getMessage());
		}

		if (!params.containsKey(BEString.fromString("port"))) {
			throw new MessageValidationException(
				ErrorMessage.FailureReason.MISSING_PORT.getMessage());
		}

		try {
			byte[] infoHash = params.get(BEString.fromString("info_hash")).getBytes();
			byte[] peerId = params.get(BEString.fromString("peer_id")).getBytes();
			int port = params.get(BEString.fromString("port")).getInt();

			// Default 'uploaded' and 'downloaded' to 0 if the client does
			// not provide it (although it should, according to the spec).
			long uploaded = 0;
			if (params.containsKey(BEString.fromString("uploaded"))) {
				uploaded = params.get(BEString.fromString("uploaded")).getLong();
			}

			long downloaded = 0;
			if (params.containsKey(BEString.fromString("downloaded"))) {
				downloaded = params.get(BEString.fromString("downloaded")).getLong();
			}

			// Default 'left' to -1 to avoid peers entering the COMPLETED
			// state when they don't provide the 'left' parameter.
			long left = -1;
			if (params.containsKey(BEString.fromString("left"))) {
				left = params.get(BEString.fromString("left")).getLong();
			}

			boolean compact = false;
			if (params.containsKey(BEString.fromString("compact"))) {
				compact = params.get(BEString.fromString("compact")).getInt() == 1;
			}

			boolean noPeerId = false;
			if (params.containsKey(BEString.fromString("no_peer_id"))) {
				noPeerId = params.get(BEString.fromString("no_peer_id")).getInt() == 1;
			}

			int numWant = AnnounceRequestMessage.DEFAULT_NUM_WANT;
			if (params.containsKey(BEString.fromString("numwant"))) {
				numWant = params.get(BEString.fromString("numwant")).getInt();
			}

			String ip = null;
			if (params.containsKey(BEString.fromString("ip"))) {
				ip = params.get(BEString.fromString("ip")).getBEString(Torrent.BYTE_ENCODING).toString();
			}

			RequestEvent event = RequestEvent.NONE;
			if (params.containsKey(BEString.fromString("event"))) {
				event = RequestEvent.getByName(params.get(BEString.fromString("event"))
					.getBEString(Torrent.BYTE_ENCODING).toString());
			}

			return new HTTPAnnounceRequestMessage(data, infoHash,
				new Peer(ip, port, ByteBuffer.wrap(peerId)),
				uploaded, downloaded, left, compact, noPeerId,
				event, numWant);
		} catch (InvalidBEncodingException ibee) {
			throw new MessageValidationException(
				"Invalid HTTP tracker request!", ibee);
		}
	}

	public static HTTPAnnounceRequestMessage craft(byte[] infoHash,
		byte[] peerId, int port, long uploaded, long downloaded, long left,
		boolean compact, boolean noPeerId, RequestEvent event,
		String ip, int numWant)
		throws IOException, MessageValidationException,
			UnsupportedEncodingException {
		Map<BEString, BEValue> params = new HashMap<BEString, BEValue>();
		params.put(BEString.fromString("info_hash"), new BEValue(infoHash));
		params.put(BEString.fromString("peer_id"), new BEValue(peerId));
		params.put(BEString.fromString("port"), new BEValue(port));
		params.put(BEString.fromString("uploaded"), new BEValue(uploaded));
		params.put(BEString.fromString("downloaded"), new BEValue(downloaded));
		params.put(BEString.fromString("left"), new BEValue(left));
		params.put(BEString.fromString("compact"), new BEValue(compact ? 1 : 0));
		params.put(BEString.fromString("no_peer_id"), new BEValue(noPeerId ? 1 : 0));

		if (event != null) {
			params.put(BEString.fromString("event"),
				new BEValue(event.getEventName(), Torrent.BYTE_ENCODING));
		}

		if (ip != null) {
			params.put(BEString.fromString("ip"),
				new BEValue(ip, Torrent.BYTE_ENCODING));
		}

		if (numWant != AnnounceRequestMessage.DEFAULT_NUM_WANT) {
			params.put(BEString.fromString("numwant"), new BEValue(numWant));
		}

		return new HTTPAnnounceRequestMessage(
			BEncoder.bencode(params),
			infoHash, new Peer(ip, port, ByteBuffer.wrap(peerId)),
			uploaded, downloaded, left, compact, noPeerId, event, numWant);
	}
}
