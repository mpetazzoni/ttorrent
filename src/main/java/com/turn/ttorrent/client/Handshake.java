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
package com.turn.ttorrent.client;

import com.turn.ttorrent.common.Torrent;

import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.text.ParseException;


/**
 * Peer handshake handler.
 *
 * @author mpetazzoni
 */
public class Handshake {

	public static final String BITTORRENT_PROTOCOL_IDENTIFIER = "BitTorrent protocol";
	public static final int BASE_HANDSHAKE_LENGTH = 49;

	ByteBuffer data;
	ByteBuffer infoHash;
	ByteBuffer peerId;

	private Handshake(ByteBuffer data, ByteBuffer infoHash,
			ByteBuffer peerId) {
		this.data = data;
		this.data.rewind();

		this.infoHash = infoHash;
		this.peerId = peerId;
	}

	public ByteBuffer getData() {
		return this.data;
	}

	public byte[] getInfoHash() {
		return this.infoHash.array();
	}

	public byte[] getPeerId() {
		return this.peerId.array();
	}

	public static Handshake parse(ByteBuffer buffer)
		throws ParseException, UnsupportedEncodingException {
		int pstrlen = Byte.valueOf(buffer.get()).intValue();
		if (pstrlen < 0 ||
				buffer.remaining() != BASE_HANDSHAKE_LENGTH + pstrlen - 1) {
			throw new ParseException("Incorrect handshake message length " +
				   "(pstrlen=" + pstrlen + ") !", 0);
		}

		// Check the protocol identification string
		byte[] pstr = new byte[pstrlen];
		buffer.get(pstr);

		if (!Handshake.BITTORRENT_PROTOCOL_IDENTIFIER.equals(
					new String(pstr, Torrent.BYTE_ENCODING))) {
			throw new ParseException("Invalid protocol identifier!", 1);
		}

		// Ignore reserved bytes
		byte[] reserved = new byte[8];
		buffer.get(reserved);

		byte[] infoHash = new byte[20];
		buffer.get(infoHash);
		byte[] peerId = new byte[20];
		buffer.get(peerId);
		return new Handshake(buffer, ByteBuffer.wrap(infoHash),
				ByteBuffer.wrap(peerId));
	}

	public static Handshake craft(byte[] torrentInfoHash,
			byte[] clientPeerId) {
		try {
			ByteBuffer buffer = ByteBuffer.allocate(
					Handshake.BASE_HANDSHAKE_LENGTH +
					Handshake.BITTORRENT_PROTOCOL_IDENTIFIER.length());

			byte[] reserved = new byte[8];
			ByteBuffer infoHash = ByteBuffer.wrap(torrentInfoHash);
			ByteBuffer peerId = ByteBuffer.wrap(clientPeerId);

			buffer.put((byte)Handshake
					.BITTORRENT_PROTOCOL_IDENTIFIER.length());
			buffer.put(Handshake
					.BITTORRENT_PROTOCOL_IDENTIFIER.getBytes(Torrent.BYTE_ENCODING));
			buffer.put(reserved);
			buffer.put(infoHash);
			buffer.put(peerId);

			return new Handshake(buffer, infoHash, peerId);
		} catch (UnsupportedEncodingException uee) {
			return null;
		}
	}
}
