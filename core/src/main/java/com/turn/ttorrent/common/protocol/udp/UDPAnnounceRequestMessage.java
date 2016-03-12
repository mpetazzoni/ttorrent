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
package com.turn.ttorrent.common.protocol.udp;

import com.turn.ttorrent.common.Torrent;
import com.turn.ttorrent.common.Utils;
import com.turn.ttorrent.common.protocol.TrackerMessage;

import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;


/**
 * The announce request message for the UDP tracker protocol.
 *
 * @author mpetazzoni
 */
public class UDPAnnounceRequestMessage
	extends UDPTrackerMessage.UDPTrackerRequestMessage
	implements TrackerMessage.AnnounceRequestMessage {

	private static final int UDP_ANNOUNCE_REQUEST_MESSAGE_SIZE = 98;

	private final long connectionId;
	private final int actionId = Type.ANNOUNCE_REQUEST.getId();
	private final int transactionId;
	private final byte[] infoHash;
	private final byte[] peerId;
	private final long downloaded;
	private final long uploaded;
	private final long left;
	private final RequestEvent event;
	private final InetAddress ip;
	private final int numWant;
	private final int key;
	private final short port;

	private UDPAnnounceRequestMessage(ByteBuffer data, long connectionId,
		int transactionId, byte[] infoHash, byte[] peerId, long downloaded,
		long uploaded, long left, RequestEvent event, InetAddress ip,
		int key, int numWant, short port) {
		super(Type.ANNOUNCE_REQUEST, data);
		this.connectionId = connectionId;
		this.transactionId = transactionId;
		this.infoHash = infoHash;
		this.peerId = peerId;
		this.downloaded = downloaded;
		this.uploaded = uploaded;
		this.left = left;
		this.event = event;
		this.ip = ip;
		this.key = key;
		this.numWant = numWant;
		this.port = port;
	}

	public long getConnectionId() {
		return this.connectionId;
	}

	@Override
	public int getActionId() {
		return this.actionId;
	}

	@Override
	public int getTransactionId() {
		return this.transactionId;
	}

	@Override
	public byte[] getInfoHash() {
		return this.infoHash;
	}

	@Override
	public String getHexInfoHash() {
		return Utils.bytesToHex(this.infoHash);
	}

	@Override
	public byte[] getPeerId() {
		return this.peerId;
	}

	@Override
	public String getHexPeerId() {
		return Utils.bytesToHex(this.peerId);
	}

	@Override
	public int getPort() {
		return this.port;
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
		return true;
	}

	@Override
	public boolean getNoPeerIds() {
		return true;
	}

	@Override
	public RequestEvent getEvent() {
		return this.event;
	}

	@Override
	public String getIp() {
		return this.ip.toString();
	}

	@Override
	public int getNumWant() {
		return this.numWant;
	}

	public int getKey() {
		return this.key;
	}

	public static UDPAnnounceRequestMessage parse(ByteBuffer data)
		throws MessageValidationException {
		if (data.remaining() != UDP_ANNOUNCE_REQUEST_MESSAGE_SIZE) {
			throw new MessageValidationException(
				"Invalid announce request message size!");
		}

		long connectionId = data.getLong();

		if (data.getInt() != Type.ANNOUNCE_REQUEST.getId()) {
			throw new MessageValidationException(
				"Invalid action code for announce request!");
		}

		int transactionId = data.getInt();
		byte[] infoHash = new byte[20];
		data.get(infoHash);
		byte[] peerId = new byte[20];
		data.get(peerId);
		long downloaded = data.getLong();
		long uploaded = data.getLong();
		long left = data.getLong();

		RequestEvent event = RequestEvent.getById(data.getInt());
		if (event == null) {
			throw new MessageValidationException(
				"Invalid event type in announce request!");
		}

		InetAddress ip = null;
		try {
			byte[] ipBytes = new byte[4];
			data.get(ipBytes);
			ip = InetAddress.getByAddress(ipBytes);
		} catch (UnknownHostException uhe) {
			throw new MessageValidationException(
				"Invalid IP address in announce request!");
		}

		int key = data.getInt();
		int numWant = data.getInt();
		short port = data.getShort();

		return new UDPAnnounceRequestMessage(data,
			connectionId,
			transactionId,
			infoHash,
			peerId,
			downloaded,
			uploaded,
			left,
			event,
			ip,
			key,
			numWant,
			port);
	}

	public static UDPAnnounceRequestMessage craft(long connectionId,
		int transactionId, byte[] infoHash, byte[] peerId, long downloaded,
		long uploaded, long left, RequestEvent event, InetAddress ip,
		int key, int numWant, int port) {
		if (infoHash.length != 20 || peerId.length != 20) {
			throw new IllegalArgumentException();
		}

		if (! (ip instanceof Inet4Address)) {
			throw new IllegalArgumentException("Only IPv4 addresses are " +
				"supported by the UDP tracer protocol!");
		}

		ByteBuffer data = ByteBuffer.allocate(UDP_ANNOUNCE_REQUEST_MESSAGE_SIZE);
		data.putLong(connectionId);
		data.putInt(Type.ANNOUNCE_REQUEST.getId());
		data.putInt(transactionId);
		data.put(infoHash);
		data.put(peerId);
		data.putLong(downloaded);
		data.putLong(left);
		data.putLong(uploaded);
		data.putInt(event.getId());
		data.put(ip.getAddress());
		data.putInt(key);
		data.putInt(numWant);
		data.putShort((short)port);
		return new UDPAnnounceRequestMessage(data,
			connectionId,
			transactionId,
			infoHash,
			peerId,
			downloaded,
			uploaded,
			left,
			event,
			ip,
			key,
			numWant,
			(short)port);
	}
}
