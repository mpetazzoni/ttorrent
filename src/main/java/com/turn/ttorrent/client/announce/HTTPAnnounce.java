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

import com.turn.ttorrent.bcodec.BDecoder;
import com.turn.ttorrent.bcodec.BEValue;
import com.turn.ttorrent.bcodec.InvalidBEncodingException;
import com.turn.ttorrent.client.SharedTorrent;
import com.turn.ttorrent.common.Torrent;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;


/**
 * Announcer for HTTP trackers.
 *
 * @author mpetazzoni
 * @see <a href="http://wiki.theory.org/BitTorrentSpecification#Tracker_Request_Parameters">BitTorrent tracker request specification</a>
 */
public class HTTPAnnounce extends Announce {

	private static final String THREAD_NAME = "bt-http-announce";

	/**
	 * Create a new HTTP announcer for the given torrent.
	 *
	 * @param torrent The torrent we're announcing about.
	 * @param id Our client peer ID.
	 * @param address Our client network address, used to extract our external
	 * IP and listening port.
	 */
	public HTTPAnnounce(SharedTorrent torrent, String peerId,
		InetSocketAddress address) {
		super(torrent, peerId, address);
	}

	/**
	 * Main announce loop.
	 *
	 * <p>
	 * The announce thread starts by making the initial 'started' announce
	 * request to register on the tracker and get the announce interval value.
	 * Subsequent announce requests are ordinary, event-less, periodic requests
	 * for peers.
	 * </p>
	 *
	 * <p>
	 * Unless forcefully stopped, the announce thread will terminate by sending
	 * a 'stopped' announce request before stopping.
	 * </p>
	 */
	@Override
	public void run() {
		Thread.currentThread().setName(HTTPAnnounce.THREAD_NAME);

		logger.info("Starting announce thread for " +
				this.torrent.getName() + " to " +
				this.torrent.getAnnounceUrl() + "...");

		// Set an initial announce interval to 5 seconds. This will be updated
		// in real-time by the tracker's responses to our announce requests.
		this.interval = 5;
		this.initial = true;

		while (!this.stop) {
			this.announce(this.initial ?
					AnnounceEvent.STARTED :
					AnnounceEvent.NONE);

			try {
				logger.trace("Sending next announce in " + this.interval +
					" seconds.");
				Thread.sleep(this.interval * 1000);
			} catch (InterruptedException ie) {
				// Ignore
			}
		}

		if (!this.forceStop) {
			// Send the final 'stopped' event to the tracker after a little
			// while.
			try {
				Thread.sleep(500);
			} catch (InterruptedException ie) {
				// Ignore
			}

			this.announce(AnnounceEvent.STOPPED, true);
		}
	}

	/**
	 * Build, send and process a tracker announce request.
	 *
	 * <p>
	 * This function first builds an announce request for the specified event
	 * with all the required parameters. Then, the request is made to the
	 * tracker's announce URL and the response is read and B-decoded.
	 * </p>
	 *
	 * <p>
	 * All registered {@link AnnounceResponseListener} objects are then fired
	 * with the decoded payload.
	 * </p>
	 *
	 * @param event The announce event type (can be AnnounceEvent.NONE for
	 * periodic updates).
	 */
	private void announce(AnnounceEvent event) {
		this.announce(event, false);
	}

	/**
	 * Build, send and process a tracker announce request.
	 *
	 * <p>
	 * Gives the ability to perform an announce request without notifying the
	 * registered listeners.
	 * </p>
	 *
	 * @see #announce(AnnounceEvent event)
	 * @param event The announce event type (can be AnnounceEvent.NONE for
	 * periodic updates).
	 * @param inhibitEvent Prevent event listeners from being notified.
	 */
	private void announce(AnnounceEvent event, boolean inhibitEvent) {
		Map<String, String> params = new HashMap<String, String>();

		try {
			params.put("info_hash",
				new String(torrent.getInfoHash(), Torrent.BYTE_ENCODING));

			// Also throw in there the hex-encoded info-hash for easier
			// debugging of announce requests.
			params.put("info_hash_hex",
				Torrent.toHexString(params.get("info_hash")));
		} catch (UnsupportedEncodingException uee) {
			logger.warn("{}", uee.getMessage());
		}

		params.put("peer_id", this.id);
		params.put("port", Integer.valueOf(this.address.getPort()).toString());
		params.put("uploaded", Long.valueOf(this.torrent.getUploaded()).toString());
		params.put("downloaded", Long.valueOf(this.torrent.getDownloaded()).toString());
		params.put("left", Long.valueOf(this.torrent.getLeft()).toString());

		if (!AnnounceEvent.NONE.equals(event)) {
			params.put("event", event.name().toLowerCase());
		}

		params.put("ip", this.address.getAddress().getHostAddress());
		params.put("compact", "1");

		Map<String, BEValue> result = null;

		try {
			logger.debug("Announcing " +
					(!AnnounceEvent.NONE.equals(event) ?
					 event.name() + " " : "") + "to tracker with " +
					this.torrent.getUploaded() + "U/" +
					this.torrent.getDownloaded() + "D/" +
					this.torrent.getLeft() + "L bytes for " +
					this.torrent.getName() + "...");
			URL announce = this.buildAnnounceURL(params);
			URLConnection conn = announce.openConnection();
			InputStream is = conn.getInputStream();
			result = BDecoder.bdecode(is).getMap();
			is.close();

			List<InetSocketAddress> peers = null;
			int interval = result.get("interval").getInt();

			try {
				// First attempt to decode a compact response, since we asked
				// for it.
				peers = this.toPeerList(result.get("peers").getBytes());
			} catch (InvalidBEncodingException ibee) {
				// Fall back to peer list, non-compact response, in case the
				// tracker did not support compact responses.
				peers = this.toPeerList(result.get("peers").getList());
			} finally {
				if (!inhibitEvent || peers != null) {
					super.fireAnnounceResponseEvent(-1, -1, interval, peers);
				}
			}
		} catch (UnsupportedEncodingException uee) {
			logger.error("{}", uee.getMessage(), uee);
			this.stop(true);
		} catch (MalformedURLException mue) {
			logger.error("{}", mue.getMessage(), mue);
			this.stop(true);
		} catch (InvalidBEncodingException ibee) {
			logger.error("Error parsing tracker response: {}",
				ibee.getMessage(), ibee);
			this.stop(true);
		} catch (IOException ioe) {
			logger.warn("Error reading response from tracker: {}",
				ioe.getMessage());
		} finally {
			// Try to get the error from the announce response and log it, when
			// it's there.
			if (result != null && result.containsKey("failure reason")) {
				try {
					logger.warn("{}",
						result.get("failure reason").getString());
				} catch (InvalidBEncodingException ibee) {
					logger.warn("Announce error, and couldn't parse " +
						"failure reason!");
				}
			}
		}
	}

	/**
	 * Build the announce request URL from the provided parameters.
	 *
	 * @param params The key/value parameters pairs in a map.
	 * @return The URL object representing the announce request URL.
	 */
	private URL buildAnnounceURL(Map<String, String> params)
		throws UnsupportedEncodingException, MalformedURLException {
		String announceURL = this.torrent.getAnnounceUrl();

		if (params.isEmpty()) {
			return new URL(announceURL);
		}

		StringBuilder url = new StringBuilder(announceURL);
		url.append(announceURL.contains("?") ? "&" : "?");

		for (Iterator<Map.Entry<String, String>> it =
			   params.entrySet().iterator() ; it.hasNext() ; ) {
			Map.Entry<String, String> param = it.next();
			url.append(param.getKey())
				.append("=")
				.append(URLEncoder.encode(param.getValue(),
							Torrent.BYTE_ENCODING));
			if (it.hasNext()) {
				url.append("&");
			}
		}

		return new URL(url.toString());
	}

	/**
	 * Build a peer list as a list of {@link InetSocketAddress} from the
	 * announce response's peer list (in non-compact mode).
	 *
	 * @param peers The list of {@link BEValue}s dictionaries describing the
	 * peers from the announce response.
	 * @return A {@link List} of {@link InetSocketAddress} representing the
	 * peers' addresses. Peer IDs are lost, but they are not crucial.
	 */
	private List<InetSocketAddress> toPeerList(List<BEValue> peers)
		throws InvalidBEncodingException, UnsupportedEncodingException {
		List<InetSocketAddress> result = new LinkedList<InetSocketAddress>();

		for (BEValue peer : peers) {
			Map<String, BEValue> peerInfo = peer.getMap();
			result.add(new InetSocketAddress(
				peerInfo.get("ip").getString(Torrent.BYTE_ENCODING),
				peerInfo.get("port").getInt()));
		}

		return result;
	}

	/**
	 * Build a peer list as a list of {@link InetSocketAddress} from the
	 * announce response's binary compact peer list.
	 *
	 * @param data The bytes representing the compact peer list from the
	 * announce response.
	 * @return A {@link List} of {@link InetSocketAddress} representing the
	 * peers' addresses. Peer IDs are lost, but they are not crucial.
	 */
	private List<InetSocketAddress> toPeerList(byte[] data)
		throws InvalidBEncodingException, UnknownHostException {
		int nPeers = data.length / 6;
		if (data.length % 6 != 0) {
			throw new InvalidBEncodingException("Invalid peers " +
				"binary information string!");
		}

		List<InetSocketAddress> result = new LinkedList<InetSocketAddress>();
		ByteBuffer peers = ByteBuffer.wrap(data);
		logger.debug("Got compact tracker response with {} peer(s).",
				nPeers);

		for (int i=0; i < nPeers ; i++) {
			byte[] ipBytes = new byte[4];
			peers.get(ipBytes);
			InetAddress ip = InetAddress.getByAddress(ipBytes);
			int port = (0xFF & (int)peers.get()) << 8
				| (0xFF & (int)peers.get());
			result.add(new InetSocketAddress(ip, port));
		}

		return result;
	}
}
