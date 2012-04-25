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
import com.turn.ttorrent.common.protocol.TrackerMessage.*;
import com.turn.ttorrent.common.protocol.http.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.ByteBuffer;

import org.apache.commons.io.input.AutoCloseInputStream;
import org.apache.commons.io.output.ByteArrayOutputStream;


/**
 * Announcer for HTTP trackers.
 *
 * @author mpetazzoni
 * @see <a href="http://wiki.theory.org/BitTorrentSpecification#Tracker_Request_Parameters">BitTorrent tracker request specification</a>
 */
public class HTTPAnnounce extends Announce {

	/**
	 * Create a new HTTP announcer for the given torrent.
	 *
	 * @param torrent The torrent we're announcing about.
	 * @param peer Our own peer specification.
	 */
	protected HTTPAnnounce(SharedTorrent torrent, Peer peer) {
		super(torrent, peer, "http");
	}

	/**
	 * Build, send and process a tracker announce request.
	 *
	 * <p>
	 * This function first builds an announce request for the specified event
	 * with all the required parameters. Then, the request is made to the
	 * tracker and the response analyzed.
	 * </p>
	 *
	 * <p>
	 * All registered {@link AnnounceResponseListener} objects are then fired
	 * with the decoded payload.
	 * </p>
	 *
	 * @see #announce(AnnounceEvent event)
	 * @param event The announce event type (can be AnnounceEvent.NONE for
	 * periodic updates).
	 * @param inhibitEvents Prevent event listeners from being notified.
	 */
	@Override
	public void announce(AnnounceRequestMessage .RequestEvent event,
		boolean inhibitEvents) {
		logger.debug("Announcing " +
			(!AnnounceRequestMessage.RequestEvent.NONE.equals(event)
				? event.name() + " "
				: "") +
			"to tracker with " +
			this.torrent.getUploaded() + "U/" +
			this.torrent.getDownloaded() + "D/" +
			this.torrent.getLeft() + "L bytes..." );

		try {
			HTTPAnnounceRequestMessage request =
				this.buildAnnounceRequest(event);

			// Send announce request (HTTP GET)
			URL target = request.buildAnnounceURL(this.torrent.getAnnounceUrl());
			URLConnection conn = target.openConnection();

			InputStream is = new AutoCloseInputStream(conn.getInputStream());
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			baos.write(is);

			// Parse and handle the response
			HTTPTrackerMessage message =
				HTTPTrackerMessage.parse(ByteBuffer.wrap(baos.toByteArray()));
			this.handleTrackerResponse(message, inhibitEvents);
		} catch (MalformedURLException mue) {
			logger.error("Invalid tracker announce URL: {}!",
				mue.getMessage(), mue);
		} catch (MessageValidationException mve) {
			logger.error("Tracker message violates expected protocol: {}!",
				mve.getMessage(), mve);
		} catch (IOException ioe) {
			logger.error("Error reading from tracker: {}!", ioe.getMessage());
		}
	}

	/**
	 * Build the announce request tracker message.
	 *
	 * @param event The announce event (can be <tt>NONE</tt> or <em>null</em>)
	 * @return Returns an instance of a {@link HTTPAnnounceRequestMessage}
	 * that can be used to generate the fully qualified announce URL, with
	 * parameters, to make the announce request.
	 * @throws UnsupportedEncodingException
	 * @throws IOException
	 * @throws MessageValidationException
	 */
	private HTTPAnnounceRequestMessage buildAnnounceRequest(
		AnnounceRequestMessage.RequestEvent event)
		throws UnsupportedEncodingException, IOException,
			MessageValidationException {
		// Build announce request message
		return HTTPAnnounceRequestMessage.craft(
				this.torrent.getInfoHash(),
				this.peer.getPeerId().array(),
				this.peer.getPort(),
				this.torrent.getUploaded(),
				this.torrent.getDownloaded(),
				this.torrent.getLeft(),
				true, false, event,
				this.peer.getIp(),
				AnnounceRequestMessage.DEFAULT_NUM_WANT,
				null, null);
	}

	/**
	 * Handle the response from the tracker.
	 *
	 * <p>
	 * Analyzes the response from the tracker and acts on it. If the response
	 * is an error, it is logged. Otherwise, the announce response is used
	 * to fire the corresponding announce and peer events to all announce
	 * listeners.
	 * </p>
	 *
	 * @param message The incoming {@link HTTPTrackerMessage}.
	 * @param inhibitEvents Whether or not to prevent events from being fired.
	 */
	private void handleTrackerResponse(HTTPTrackerMessage message,
		boolean inhibitEvents) {
		if (message instanceof ErrorMessage) {
			logger.warn("Error reported by tracker: {}",
				((ErrorMessage)message).getReason());
		} else if (message instanceof AnnounceResponseMessage) {
			AnnounceResponseMessage response =
				(AnnounceResponseMessage)message;

			if (inhibitEvents) {
				return;
			}

			this.fireAnnounceResponseEvent(
				response.getComplete(),
				response.getIncomplete(),
				response.getInterval());
			this.fireDiscoveredPeersEvent(
				response.getPeers());
		} else {
			logger.error("Unexpected tracker message type ({})!",
				message.getType().name());
		}
	}
}
