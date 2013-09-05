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

import com.turn.ttorrent.common.Peer;
import com.turn.ttorrent.common.Torrent;
import com.turn.ttorrent.common.TorrentInfo;
import com.turn.ttorrent.common.protocol.TrackerMessage.*;
import com.turn.ttorrent.common.protocol.http.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;

import org.apache.commons.io.FileUtils;
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
	 * @param peer Our own peer specification.
	 */
  public HTTPTrackerClient(Peer peer, URI tracker) {
    super(peer, tracker);
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
     * @param torrent
     */
	public void announce(AnnounceRequestMessage.RequestEvent event,
                       boolean inhibitEvents, TorrentInfo torrentInfo) throws AnnounceException {
      logAnnounceRequest(event, torrentInfo);
      URL target = null;
		try {
			HTTPAnnounceRequestMessage request =
            this.buildAnnounceRequest(event, torrentInfo);
			target = request.buildAnnounceURL(this.tracker.toURL());
		} catch (MalformedURLException mue) {
			throw new AnnounceException("Invalid announce URL (" +
				mue.getMessage() + ")", mue);
		} catch (MessageValidationException mve) {
			throw new AnnounceException("Announce request creation violated " +
				"expected protocol (" + mve.getMessage() + ")", mve);
		} catch (IOException ioe) {
			throw new AnnounceException("Error building announce request (" +
				ioe.getMessage() + ")", ioe);
		}

		HttpURLConnection conn = null;
		InputStream in = null;
		try {
			conn = (HttpURLConnection)target.openConnection();
			in = conn.getInputStream();
		} catch (IOException ioe) {
			if (conn != null) {
				in = conn.getErrorStream();
			}
		}

		// At this point if the input stream is null it means we have neither a
		// response body nor an error stream from the server. No point in going
		// any further.
		if (in == null) {
			throw new AnnounceException("No response or unreachable tracker!");
		}

		try {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();

      byte[] buf = new byte[8192];
      int len;
      while ((len=in.read(buf)) > 0){
        baos.write(buf, 0, len);
      }

			// Parse and handle the response
			HTTPTrackerMessage message =
				HTTPTrackerMessage.parse(ByteBuffer.wrap(baos.toByteArray()));
      this.handleTrackerAnnounceResponse(message, inhibitEvents, torrentInfo.getHexInfoHash());
		} catch (IOException ioe) {
			throw new AnnounceException("Error reading tracker response!", ioe);
		} catch (MessageValidationException mve) {
			throw new AnnounceException("Tracker message violates expected " +
				"protocol (" + mve.getMessage() + ")", mve);
		} finally {
			// Make sure we close everything down at the end to avoid resource
			// leaks.
			try {
				in.close();
			} catch (IOException ioe) {
				logger.warn("Problem ensuring error stream closed!", ioe);
			}

			// This means trying to close the error stream as well.
			InputStream err = conn.getErrorStream();
			if (err != null) {
				try {
					err.close();
				} catch (IOException ioe) {
					logger.warn("Problem ensuring error stream closed!", ioe);
				}
			}
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
    AnnounceRequestMessage.RequestEvent event, TorrentInfo torrentInfo)
		throws IOException,
			MessageValidationException {
		// Build announce request message
      final long uploaded = torrentInfo.getUploaded();
      final long downloaded = torrentInfo.getDownloaded();
      final long left = torrentInfo.getLeft();
      return HTTPAnnounceRequestMessage.craft(
      torrentInfo.getInfoHash(),
          this.peer.getPeerIdArray(),
          this.peer.getPort(),
          uploaded,
          downloaded,
          left,
          true, false, event,
          this.peer.getIp(),
          AnnounceRequestMessage.DEFAULT_NUM_WANT);
    }
}
