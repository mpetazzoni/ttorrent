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

package com.turn.bittorrent.common;

import java.nio.ByteBuffer;

/** A basic BitTorrent peer.
 *
 * This class is meant to be a common base for the tracker and client, which
 * would presumably subclass it to extend its functionnality and fields.
 *
 * @author mpetazzoni
 */
public class Peer {

	private ByteBuffer peerId;
	private String hexPeerId;
	private String ip;
	private int port;

	/** Instanciate a new peer for the given torrent.
	 *
	 * @param peerId The byte-encoded peer ID.
	 * @param hexPeerId The hexadecimal encoded string representation of
	 * the peer ID.
	 * @param ip The peer's IP address.
	 * @param port The peer's port.
	 */
	public Peer(ByteBuffer peerId, String hexPeerId, String ip, int port) {
		this.peerId = peerId;
		this.hexPeerId = hexPeerId;
		this.ip = ip;
		this.port = port;
	}

	public ByteBuffer getPeerId() {
		return this.peerId;
	}

	/** Get the hexadecimal-encoded string representation of this peer's ID.
	 */
	public String getHexPeerId() {
		return this.hexPeerId;
	}

	public String getIp() {
		return this.ip;
	}

	public int getPort() {
		return this.port;
	}

	/** Return a human-readable representation of this peer.
	 */
	public String toString() {
		return new StringBuilder("peer://")
			.append(this.ip).append(":").append(this.port)
			.append("/-")
			.append(this.hexPeerId.substring(this.hexPeerId.length()-6))
			.toString();
	}

	/** Tells if two peers seem to lookalike, i.e. they have the same IP and
	 * same port.
	 */
	public boolean looksLike(Peer other) {
		if (other == null) {
			return false;
		}

		return this.ip.equals(other.getIp()) && this.port == other.getPort();
	}
}
