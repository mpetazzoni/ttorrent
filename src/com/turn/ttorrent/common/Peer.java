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

package com.turn.ttorrent.common;

import com.turn.ttorrent.common.Torrent;

import java.nio.ByteBuffer;

/** A basic BitTorrent peer.
 *
 * This class is meant to be a common base for the tracker and client, which
 * would presumably subclass it to extend its functionnality and fields.
 *
 * @author mpetazzoni
 */
public class Peer {

	private final String ip;
	private final int port;
	private final String hostId;

	private ByteBuffer peerId;
	private String hexPeerId;

	/** Instanciate a new peer for the given torrent.
	 *
	 * @param ip The peer's IP address.
	 * @param port The peer's port.
	 * @param peerId The byte-encoded peer ID.
	 */
	public Peer(String ip, int port, ByteBuffer peerId) {
		this.ip = ip;
		this.port = port;
		this.hostId = String.format("%s:%d", ip, port);

		this.setPeerId(peerId);
	}

	/** Tells whether this peer has a known peer ID yet or not.
	 */
	public boolean hasPeerId() {
		return this.peerId != null;
	}

	/** Returns the raw peer ID as a {@link ByteBuffer}.
	 */
	public ByteBuffer getPeerId() {
		return this.peerId;
	}

	/** Set a peer ID for this peer (usually during handshake).
	 *
	 * @param peerId The new peer ID for this peer.
	 */
	public void setPeerId(ByteBuffer peerId) {
		if (peerId != null) {
			this.peerId = peerId;
			this.hexPeerId = Torrent.byteArrayToHexString(peerId.array());
		} else {
			this.peerId = null;
			this.hexPeerId = null;
		}
	}

	/** Get the hexadecimal-encoded string representation of this peer's ID.
	 */
	public String getHexPeerId() {
		return this.hexPeerId;
	}

	/** Returns this peer's IP address.
	 */
	public String getIp() {
		return this.ip;
	}

	/** Returns this peer's port number.
	 */
	public int getPort() {
		return this.port;
	}

	/** Returns this peer's host identifier ("host:port").
	 */
	public String getHostIdentifier() {
		return this.hostId;
	}

	/** Returns a human-readable representation of this peer.
	 */
	public String toString() {
		StringBuilder s = new StringBuilder("peer://")
			.append(this.ip).append(":").append(this.port)
			.append("/");

		if (this.hasPeerId()) {
			s.append(this.hexPeerId.substring(this.hexPeerId.length()-6));
		} else {
			s.append("?");
		}

		return s.toString();
	}

	/** Tells if two peers seem to lookalike (i.e. they have the same IP, port
	 * and peer ID if they have one).
	 */
	public boolean looksLike(Peer other) {
		if (other == null) {
			return false;
		}

		return this.hostId.equals(other.hostId) &&
			(this.hasPeerId()
				 ? this.hexPeerId.equals(other.hexPeerId)
				 : true);
	}
}
