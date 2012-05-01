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
package com.turn.ttorrent.client.announce;

import com.turn.ttorrent.client.SharedTorrent;
import com.turn.ttorrent.common.Peer;
import com.turn.ttorrent.common.protocol.TrackerMessage;
import com.turn.ttorrent.common.protocol.TrackerMessage.*;
import com.turn.ttorrent.common.protocol.udp.*;

import java.io.IOException;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.UnsupportedAddressTypeException;
import java.util.Date;
import java.util.Random;

/**
 * Announcer for UDP trackers.
 *
 * <p>
 * The UDP tracker protocol requires a two-step announce request/response
 * exchange where the peer is first required to establish a "connection"
 * with the tracker by sending a connection request message and retreiving
 * a connection ID from the tracker to use in the following announce
 * request messages (valid for 2 minutes).
 * </p>
 *
 * <p>
 * It also contains a backing-off retry mechanism (on a 15*2^n seconds
 * scheme), in which if the announce request times-out for more than the
 * connection ID validity period, another connection request/response
 * exchange must be made before attempting to retransmit the announce
 * request.
 * </p>
 *
 * @author mpetazzoni
 */
public class UDPAnnounce extends Announce {

	/**
	 * Back-off timeout uses 15 * 2 ^ n formula.
	 */
	private static final int UDP_BASE_TIMEOUT_SECONDS = 15;

	/**
	 * We don't try more than 8 times (3840 seconds, as per the formula defined
	 * for the backing-off timeout.
	 *
	 * @see #UDP_BASE_TIMEOUT_SECONDS
	 */
	private static final int UDP_MAX_TRIES = 8;

	private final InetSocketAddress address;
	private final DatagramSocket socket;
	private final Random random;

	private Date lastConnectionTime;
	private long connectionId;

	private enum State {
		CONNECT_REQUEST,
		ANNOUNCE_REQUEST;
	};

	/**
	 * 
	 * @param torrent
	 */
	protected UDPAnnounce(SharedTorrent torrent, Peer peer)
		throws SocketException, UnknownHostException {
		super(torrent, peer, "udp");

		/**
		 * The UDP announce request protocol only supports IPv4
		 *
		 * @see http://bittorrent.org/beps/bep_0015.html#ipv6
		 */
		if (! (InetAddress.getByName(peer.getIp()) instanceof Inet4Address)) {
			throw new UnsupportedAddressTypeException();
		}

		URL announceURL = this.torrent.getAnnounceUrl();
		this.address = new InetSocketAddress(
			announceURL.getHost(),
			announceURL.getPort());

		this.socket = new DatagramSocket();
		this.socket.connect(this.address);

		this.random = new Random();
		this.lastConnectionTime = null;
	}

	@Override
	public void announce(AnnounceRequestMessage.RequestEvent event,
		boolean inhibitEvents) throws AnnounceException {
		logger.info("Announcing{} to tracker with {}U/{}D/{}L bytes...",
			new Object[] {
				this.formatAnnounceEvent(event),
				this.torrent.getUploaded(),
				this.torrent.getDownloaded(),
				this.torrent.getLeft()
			});

		try {
			State state = State.CONNECT_REQUEST;
			int tries = 0;

			while (tries <= UDP_MAX_TRIES) {
				if (this.lastConnectionTime != null &&
					new Date().before(this.lastConnectionTime)) {
					state = State.ANNOUNCE_REQUEST;
				}

				tries++;
			}



			ByteBuffer data = null;
			UDPTrackerMessage.UDPTrackerResponseMessage message =
				UDPTrackerMessage.UDPTrackerResponseMessage.parse(data);
			this.handleTrackerResponse(message, inhibitEvents);
		} catch (MessageValidationException mve) {
			logger.error("Tracker message violates expected protocol: {}!",
				mve.getMessage(), mve);
		}
	}

	private void send(TrackerMessage message)
		throws IOException, SocketException {
		byte[] data = message.getData().array();
		this.socket.send(new DatagramPacket(data, data.length, this.address));
	}

}
