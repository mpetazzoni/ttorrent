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
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.nio.ByteBuffer;

import org.apache.commons.io.input.AutoCloseInputStream;
import org.apache.commons.io.output.ByteArrayOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Announcer for HTTP trackers.
 *
 * @author mpetazzoni
 * @see <a href="http://wiki.theory.org/BitTorrentSpecification#Tracker_Request_Parameters">BitTorrent tracker request specification</a>
 */
public class HTTPTrackerClient extends TrackerClient {

	protected static final Logger logger =
		LoggerFactory.getLogger(HTTPTrackerClient.class);

	/**
	 * Create a new HTTP announcer for the given torrent.
	 *
	 * @param torrent The torrent we're announcing about.
	 * @param peer Our own peer specification.
	 */
	protected HTTPTrackerClient(SharedTorrent torrent, Peer peer,
		URI tracker) {
		super(torrent, peer, tracker);
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
	 * @param event The announce event type (can be AnnounceEvent.NONE for
	 * periodic updates).
	 * @param inhibitEvents Prevent event listeners from being notified.
	 */
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
			HTTPAnnounceRequestMessage request =
				this.buildAnnounceRequest(event);

			// Send announce request (HTTP GET)
			URL target = request.buildAnnounceURL(this.tracker.toURL());
			URLConnection conn = target.openConnection();

			InputStream is = new AutoCloseInputStream(conn.getInputStream());
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			baos.write(is);

			// Parse and handle the response
			HTTPTrackerMessage message =
				HTTPTrackerMessage.parse(ByteBuffer.wrap(baos.toByteArray()));
			this.handleTrackerAnnounceResponse(message, inhibitEvents);
		} catch (MalformedURLException mue) {
			throw new AnnounceException("Invalid announce URL (" +
				mue.getMessage() + ")", mue);
		} catch (MessageValidationException mve) {
			throw new AnnounceException("Tracker message violates expected " +
				"protocol (" + mve.getMessage() + ")", mve);
		} catch (IOException ioe) {
			throw new AnnounceException(ioe.getMessage(), ioe);
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
				AnnounceRequestMessage.DEFAULT_NUM_WANT);
	}
}
