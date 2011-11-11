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

package com.turn.ttorrent.tracker;

import com.turn.ttorrent.bcodec.BEValue;
import com.turn.ttorrent.bcodec.BEncoder;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.simpleframework.http.Request;
import org.simpleframework.http.Response;
import org.simpleframework.http.Status;
import org.simpleframework.http.core.Container;

/** Tracker service to serve the tracker's announce requests.
 *
 * <p>
 * It only serves announce requests on /announce, and only serves torrents the
 * Tracker knows about.
 * </p>
 *
 * <p>
 * The list torrents of torrents is a map of torrent hashes to their
 * corresponding Torrent objects, and is maintained by the Tracker this service
 * is part of. The TrackerService only has a reference to this map, and does
 * not modify it.
 * </p>
 *
 * @author mpetazzoni
 * @see <a href="http://wiki.theory.org/BitTorrentSpecification">BitTorrent protocol specification</a>
 */
public class TrackerService implements Container {

	private static final Logger logger =
		LoggerFactory.getLogger(TrackerService.class);

	private static final String WILDCARD_IPV4_ADDRESS = "0.0.0.0";

	private final String version;
	private final ConcurrentMap<String, TrackedTorrent> torrents;

	/** The various tracker error states.
	 *
	 * These errors are reported by the tracker to a client when expected
	 * parameters or conditions are not present while processing an announce
	 * request from a BitTorrent client.
	 */
	private enum TrackerError {
		UNKNOWN_TORRENT("The requested torrent does not exist on this tracker"),
		MISSING_HASH("Missing info hash"),
		MISSING_PEER_ID("Missing peer ID"),
		MISSING_PORT("Missing port"),
		INVALID_EVENT("Unexpected event for peer state"),
		NOT_IMPLEMENTED("Feature not implemented");

		private String message;

		TrackerError(String message) {
			this.message = message;
		}

		String getMessage() {
			return this.message;
		}

		BEValue toBEValue() throws UnsupportedEncodingException {
			Map<String, BEValue> result = new HashMap<String, BEValue>();
			result.put("failure reason", new BEValue(this.message, "UTF-8"));
			return new BEValue(result);
		}
	};

	/** Create a new TrackerService serving the given torrents.
	 *
	 * @param torrents The torrents this TrackerService should serve requests
	 * for.
	 */
	TrackerService(String version,
			ConcurrentMap<String, TrackedTorrent> torrents) {
		this.version = version;
		this.torrents = torrents;
	}

	/** Handle the incoming request on the tracker service.
	 *
	 * This makes sure the request is made to the tracker's announce URL, and
	 * delegates handling of the request to the <em>process()</em> method after
	 * preparing the response object.
	 *
	 * @param request The incoming HTTP request.
	 * @param response The response object.
	 */
	public void handle(Request request, Response response) {
		// Reject non-announce requests
		if (!Tracker.ANNOUNCE_URL.equals(request.getPath().toString())) {
			response.setCode(404);
			response.setText("Not Found");
			return;
		}

		OutputStream body = null;
		try {
			body = response.getOutputStream();
			this.process(request, response, body);
			body.flush();
		} catch (IOException ioe) {
			logger.warn("Error while writing response: {}!", ioe.getMessage());
		} finally {
			if (body != null) {
				try {
					body.close();
				} catch (IOException ioe) {
					// Ignore
				}
			}
		}
	}

	/** Process the announce request.
	 *
	 * @param request The incoming announce request.
	 * @param response The response object.
	 * @param body The validated response body output stream.
	 */
	private void process(Request request, Response response,
			OutputStream body) throws IOException {
		/* Parse the query parameters.
		 *
		 * Unfortunately we need to rely on our own parsing function here
		 * because SimpleHTTP's Query map will contain UTF-8 decoded
		 * parameters, which doesn't work well for the byte-encoded strings we
		 * expect.
		 */
		Map<String, String> params = this.parseQuery(
				request.getAddress().toString());

		// Prepare the response headers.
		response.set("Content-Type", "text/plain");
		response.set("Server", this.version);
		response.setDate("Date", System.currentTimeMillis());

		// Validate the announce request coming from the client.
		TrackerError error = this.validateAnnounceRequest(params);
		if (error != null) {
			this.serveError(response, body, Status.BAD_REQUEST, error);
			return;
		}

		// Make sure we have the peer IP, fallbacking on the request's source
		// address if the peer didn't provide it.
		if (!params.containsKey("ip") ||
			WILDCARD_IPV4_ADDRESS.equals(params.get("ip"))) {
			params.put("ip", request.getClientAddress().getAddress()
					.getHostAddress());
		}

		// Grab the corresponding torrent (validateAnnounceRequest already made
		// sure we knew about this Torrent)
		TrackedTorrent torrent = this.torrents.get(params.get("info_hash_hex"));
		if (torrent == null) {
			this.serveError(response, body, Status.INTERNAL_SERVER_ERROR,
					TrackerError.UNKNOWN_TORRENT);
			return;
		}

		ByteBuffer peerId = ByteBuffer.wrap(params.get("peer_id")
				.getBytes(TrackedTorrent.BYTE_ENCODING));
		// Update the torrent according to the announce event
		TrackedPeer peer = torrent.update(params.get("event"),
				peerId, params.get("peer_id_hex"),
				params.get("ip"), Integer.parseInt(params.get("port")),
				Long.parseLong(params.get("uploaded")),
				Long.parseLong(params.get("downloaded")),
				Long.parseLong(params.get("left"))
				);

		// Craft and output the answer
		BEncoder.bencode(torrent.peerAnswerAsBEValue(peer), body);
	}

	/** Parse the query parameters using our defined BYTE_ENCODING.
	 *
	 * Because we're expecting byte-encoded strings as query parameters, we
	 * can't rely on SimpleHTTP's QueryParser which uses the wrong encoding for
	 * the job and returns us unparsable byte data. We thus have to implement
	 * our own little parsing method that uses BYTE_ENCODING to decode
	 * parameters from the URI.
	 *
	 * <b>Note:</b> array parameters are not supported. If a key is present
	 * multiple times in the URI, the latest value prevails. We don't really
	 * need to implement this functionality as this never happens in the
	 * Tracker HTTP protocol.
	 *
	 * @param uri The request's full URI, including query parameters.
	 * @return A map of key/value pairs representing the query parameters.
	 */
	private Map<String, String> parseQuery(String uri) {
		Map<String, String> params = new HashMap<String, String>();

		try {
			for (String pair : uri.split("[?]")[1].split("&")) {
				String[] keyval = pair.split("[=]", 2);
				if (keyval.length == 1) {
					params.put(keyval[0], null);
				} else {
					try {
						params.put(keyval[0], URLDecoder.decode(keyval[1],
								TrackedTorrent.BYTE_ENCODING));
					} catch (UnsupportedEncodingException uee) {
						// Ignore, act like parameter was not there
					}
				}
			}
		} catch (ArrayIndexOutOfBoundsException e) {
			params.clear();
		}

		return params;
	}

	/** Write a TrackerError to the response with the given HTTP status code.
	 *
	 * @param response The HTTP response object.
	 * @param body The response output stream to write to.
	 * @param status The HTTP status code to return.
	 * @param error The error reported by the tracker.
	 */
	private void serveError(Response response, OutputStream body,
			Status status, TrackerError error) throws IOException {
		response.setCode(status.getCode());
		response.setText(status.getDescription());
		logger.warn("Could not process announce request ({}) !",
			error.getMessage());
		BEncoder.bencode(error.toBEValue(), body);
	}


	/** Validates the incoming announce request.
	 *
	 * The announce request must follow the BitTorrent protocol and contain a
	 * certain number of query parameters needed for processing the request.
	 * This method makes sure everything is present, and otherwise returns the
	 * appropriate error code as a <em>TrackerError</em> value.
	 *
	 * @param params The parsed query string.
	 * @return A <em>TrackerError</em> representing the error, or null if no
	 * error was detected.
	 */
	private TrackerError validateAnnounceRequest(Map<String, String> params) {
		// Torrent info hash, peer ID and peer port must all be present, and we
		// must know about the torrent referenced by the provided torrent info
		// hash.
		if (!params.containsKey("info_hash")) {
			return TrackerError.MISSING_HASH;
		}

		params.put("info_hash_hex", TrackedTorrent
				.toHexString(params.get("info_hash")));
		TrackedTorrent torrent = this.torrents.get(params.get("info_hash_hex"));
		if (torrent == null) {
			logger.warn("Requested torrent hash was: {}",
				params.get("info_hash_hex"));
			return TrackerError.UNKNOWN_TORRENT;
		}

		if (!params.containsKey("peer_id")) {
			return TrackerError.MISSING_PEER_ID;
		}
		params.put("peer_id_hex", TrackedTorrent
				.toHexString(params.get("peer_id")));

		if (!params.containsKey("port")) {
			return TrackerError.MISSING_PORT;
		}

		// Default 'uploaded' and 'downloaded' to 0 if the client does not
		// provide it (although it should, according to the spec).
		if (!params.containsKey("uploaded")) {
			params.put("uploaded", "0");
		}

		if (!params.containsKey("downloaded")) {
			params.put("downloaded", "0");
		}

		// Default 'left' to -1 to avoid peers entering the COMPLETED state
		// when they don't provide the 'left' parameter.
		if (!params.containsKey("left")) {
			params.put("left", "-1");
		}

		String event = params.get("event");
		String peerId = params.get("peer_id_hex");

		// When no event is specified, it's a periodic update while the client
		// is operating. If we don't have a peer for this announce, it means
		// the tracker restarted while the client was running. Consider this
		// announce request as a 'started' event.
		if (event == null && torrent.getPeer(peerId) == null) {
			params.put("event", "started");
		}

		// If an event other than 'started' is specified and we also haven't
		// seen the peer on this torrent before, something went wrong. A
		// previous 'started' announce request should have been made by the
		// client that would have had us register that peer on the torrent this
		// request refers to.
		if (event != null && !"started".equals(event) &&
				torrent.getPeer(peerId) == null) {
			return TrackerError.INVALID_EVENT;
		}

		return null;
	}
}
