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

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.channels.UnsupportedAddressTypeException;


/**
 * Announcer for UDP trackers.
 *
 * @author mpetazzoni
 */
public class UDPAnnounce extends Announce {

	/**
	 * 
	 * @param torrent
	 */
	protected UDPAnnounce(SharedTorrent torrent, Peer peer)
		throws UnknownHostException {
		super(torrent, peer, "udp");

		/**
		 * The UDP announce request protocol only supports IPv4
		 *
		 * @see http://bittorrent.org/beps/bep_0015.html#ipv6
		 */
		if (! (InetAddress.getByName(peer.getIp()) instanceof Inet4Address)) {
			throw new UnsupportedAddressTypeException();
		}
	}

	@Override
	public void announce(TrackerMessage.AnnounceRequestMessage
		.RequestEvent event, boolean inhibitEvents) {

	}
}
