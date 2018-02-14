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
package com.turn.ttorrent.tracker;

import com.turn.ttorrent.bcodec.BEValue;
import com.turn.ttorrent.common.LoggerUtils;
import com.turn.ttorrent.common.Peer;
import com.turn.ttorrent.common.Torrent;
import com.turn.ttorrent.common.protocol.TrackerMessage.AnnounceRequestMessage;
import com.turn.ttorrent.common.protocol.TrackerMessage.ErrorMessage;
import com.turn.ttorrent.common.protocol.TrackerMessage.MessageValidationException;
import com.turn.ttorrent.common.protocol.http.HTTPAnnounceRequestMessage;
import com.turn.ttorrent.common.protocol.http.HTTPAnnounceResponseMessage;
import com.turn.ttorrent.common.protocol.http.HTTPTrackerErrorMessage;
import org.simpleframework.http.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;


/**
 * Tracker service to serve the tracker's announce requests.
 *
 * <p>
 * It only serves announce requests on /announce, and only serves torrents the
 * {@link Tracker} it serves knows about.
 * </p>
 *
 * <p>
 * The list of torrents {@see #requestHandler.getTorrentsMap()} is a map of torrent hashes to their
 * corresponding Torrent objects, and is maintained by the {@link Tracker} this
 * service is part of. The TrackerRequestProcessor only has a reference to this map, and
 * does not modify it.
 * </p>
 *
 * @author mpetazzoni
 * @see <a href="http://wiki.theory.org/BitTorrentSpecification">BitTorrent protocol specification</a>
 */
public class TrackerRequestProcessor {

	private static final Logger logger =
		LoggerFactory.getLogger(TrackerRequestProcessor.class);

	/**
	 * The list of announce request URL fields that need to be interpreted as
	 * numeric and thus converted as such in the request message parsing.
	 */
	private static final String[] NUMERIC_REQUEST_FIELDS =
		new String[] {
			"port", "uploaded", "downloaded", "left",
			"compact", "no_peer_id", "numwant"
		};
  private static final int SEEDER_ANNOUNCE_INTERVAL = 150;

  private boolean myAcceptForeignTorrents=true; //default to true
  private int myAnnounceInterval = 60; //default value
	private final AddressChecker myAddressChecker;
	private final TorrentsRepository myTorrentsRepository;


	/**
	 * Create a new TrackerRequestProcessor serving the given torrents.
	 *
	 */
	public TrackerRequestProcessor(TorrentsRepository torrentsRepository) {
		this(torrentsRepository, new AddressChecker() {
			@Override
			public boolean isBadAddress(String ip) {
				return false;
			}
		});
	}

	public TrackerRequestProcessor(TorrentsRepository torrentsRepository, AddressChecker addressChecker) {
		myTorrentsRepository = torrentsRepository;
		myAddressChecker = addressChecker;
	}

	/**
	 * Process the announce request.
	 *
	 * <p>
	 * This method attemps to read and parse the incoming announce request into
	 * an announce request message, then creates the appropriate announce
	 * response message and sends it back to the client.
	 * </p>
	 *
	 */
	public void process(final String uri, final String hostAddress, RequestHandler requestHandler)
          throws IOException {
		// Prepare the response headers.

		/**
		 * Parse the query parameters into an announce request message.
		 *
		 * We need to rely on our own query parsing function because
		 * SimpleHTTP's Query map will contain UTF-8 decoded parameters, which
		 * doesn't work well for the byte-encoded strings we expect.
		 */
		HTTPAnnounceRequestMessage announceRequest = null;
		try {
			announceRequest = this.parseQuery(uri, hostAddress);
		} catch (MessageValidationException mve) {
			LoggerUtils.warnAndDebugDetails(logger, "Unable to parse request message. Request url is {}", uri, mve);
      serveError(Status.BAD_REQUEST, mve.getMessage(), requestHandler);
			return;
		}

		AnnounceRequestMessage.RequestEvent event = announceRequest.getEvent();

		if (event == null) {
			event = AnnounceRequestMessage.RequestEvent.NONE;
		}
		TrackedTorrent torrent = myTorrentsRepository.getTorrent(announceRequest.getHexInfoHash());

		// The requested torrent must be announced by the tracker if and only if myAcceptForeignTorrents is false
		if (!this.myAcceptForeignTorrents && torrent == null) {
			logger.warn("Requested torrent hash was: {}", announceRequest.getHexInfoHash());
			serveError(Status.BAD_REQUEST, ErrorMessage.FailureReason.UNKNOWN_TORRENT, requestHandler);
			return;
		}

		final boolean isSeeder = (event == AnnounceRequestMessage.RequestEvent.COMPLETED)
						|| (announceRequest.getLeft() == 0);

		if (myAddressChecker.isBadAddress(announceRequest.getIp())) {
			if (torrent == null) {
				writeEmptyResponse(announceRequest, requestHandler);
			} else {
				writeAnnounceResponse(torrent, null, isSeeder, requestHandler);
			}
			return;
		}

		final Peer peer = new Peer(announceRequest.getIp(), announceRequest.getPort());

		try {
			torrent = myTorrentsRepository.putIfAbsentAndUpdate(announceRequest.getHexInfoHash(), new TrackedTorrent(announceRequest.getInfoHash()),event,
							ByteBuffer.wrap(announceRequest.getPeerId()),
							announceRequest.getHexPeerId(),
							announceRequest.getIp(),
							announceRequest.getPort(),
							announceRequest.getUploaded(),
							announceRequest.getDownloaded(),
							announceRequest.getLeft());
		} catch (IllegalArgumentException iae) {
			LoggerUtils.warnAndDebugDetails(logger, "Unable to update peer torrent. Request url is {}", uri, iae);
      serveError(Status.BAD_REQUEST, ErrorMessage.FailureReason.INVALID_EVENT, requestHandler);
			return;
		}

		// Craft and output the answer
    writeAnnounceResponse(torrent, peer, isSeeder, requestHandler);
	}

	private void writeEmptyResponse(HTTPAnnounceRequestMessage announceRequest, RequestHandler requestHandler) throws IOException {
		HTTPAnnounceResponseMessage announceResponse;
		try {
			announceResponse = HTTPAnnounceResponseMessage.craft(
							myAnnounceInterval,
							0,
							0,
							Collections.<Peer>emptyList(),
							announceRequest.getHexInfoHash());
			requestHandler.serveResponse(Status.OK.getCode(), Status.OK.getDescription(), announceResponse.getData());
		} catch (Exception e) {
			serveError(Status.INTERNAL_SERVER_ERROR, e.getMessage(), requestHandler);
		}
	}

	public void setAnnounceInterval(int announceInterval) {
    myAnnounceInterval = announceInterval;
  }

  public int getAnnounceInterval() {
    return myAnnounceInterval;
  }

  private void writeAnnounceResponse(TrackedTorrent torrent, Peer peer, boolean isSeeder, RequestHandler requestHandler) throws IOException {
		HTTPAnnounceResponseMessage announceResponse = null;
		try {
			announceResponse = HTTPAnnounceResponseMessage.craft(
				isSeeder ? SEEDER_ANNOUNCE_INTERVAL : myAnnounceInterval,
        torrent.seeders(),
				torrent.leechers(),
        isSeeder ? Collections.<Peer>emptyList() : torrent.getSomePeers(peer),
        torrent.getHexInfoHash());
      requestHandler.serveResponse(Status.OK.getCode(), Status.OK.getDescription(), announceResponse.getData());
		} catch (Exception e) {
			serveError(Status.INTERNAL_SERVER_ERROR, e.getMessage(), requestHandler);
		}
	}

	/**
	 * Parse the query parameters using our defined BYTE_ENCODING.
	 *
	 * <p>
	 * Because we're expecting byte-encoded strings as query parameters, we
	 * can't rely on SimpleHTTP's QueryParser which uses the wrong encoding for
	 * the job and returns us unparsable byte data. We thus have to implement
	 * our own little parsing method that uses BYTE_ENCODING to decode
	 * parameters from the URI.
	 * </p>
	 *
	 * <p>
	 * <b>Note:</b> array parameters are not supported. If a key is present
	 * multiple times in the URI, the latest value prevails. We don't really
	 * need to implement this functionality as this never happens in the
	 * Tracker HTTP protocol.
	 * </p>
	 *
	 *
   *
   * @param uri
   * @param hostAddress
   * @return The {@link AnnounceRequestMessage} representing the client's
	 * announce request.
	 */
	private HTTPAnnounceRequestMessage parseQuery(final String uri, final String hostAddress)
		throws IOException, MessageValidationException {
		Map<String, BEValue> params = new HashMap<String, BEValue>();

		try {
//			String uri = request.getAddress().toString();
			for (String pair : uri.split("[?]")[1].split("&")) {
				String[] keyval = pair.split("[=]", 2);
				if (keyval.length == 1) {
					this.recordParam(params, keyval[0], null);
				} else {
					this.recordParam(params, keyval[0], keyval[1]);
				}
			}
		} catch (ArrayIndexOutOfBoundsException e) {
			params.clear();
		}

		// Make sure we have the peer IP, fallbacking on the request's source
		// address if the peer didn't provide it.
		if (params.get("ip") == null) {
			params.put("ip", new BEValue(
				hostAddress,
//				request.getClientAddress().getAddress().getHostAddress(),
				Torrent.BYTE_ENCODING));
		}

		return HTTPAnnounceRequestMessage.parse(new BEValue(params));
	}

	private void recordParam(Map<String, BEValue> params, String key, String value) {
		try {
			value = URLDecoder.decode(value, Torrent.BYTE_ENCODING);

			for (String f : NUMERIC_REQUEST_FIELDS) {
				if (f.equals(key)) {
					params.put(key, new BEValue(Long.valueOf(value)));
					return;
				}
			}

			params.put(key, new BEValue(value, Torrent.BYTE_ENCODING));
		} catch (UnsupportedEncodingException uee) {
			// Ignore, act like parameter was not there
			return;
		}
	}

	/**
	 * Write a {@link HTTPTrackerErrorMessage} to the response with the given
	 * HTTP status code.
	 *
	 * @param status The HTTP status code to return.
	 * @param error The error reported by the tracker.
	 */
	private void serveError(Status status, HTTPTrackerErrorMessage error, RequestHandler requestHandler) throws IOException {
    requestHandler.serveResponse(status.getCode(), status.getDescription(), error.getData());
	}

	/**
	 * Write an error message to the response with the given HTTP status code.
	 *
	 * @param status The HTTP status code to return.
	 * @param error The error message reported by the tracker.
	 */
	private void serveError(Status status, String error, RequestHandler requestHandler) throws IOException {
		try {
			this.serveError(status, HTTPTrackerErrorMessage.craft(error), requestHandler);
		} catch (MessageValidationException mve) {
			logger.warn("Could not craft tracker error message!", mve);
		}
	}

	/**
	 * Write a tracker failure reason code to the response with the given HTTP
	 * status code.
	 *
	 * @param status The HTTP status code to return.
	 * @param reason The failure reason reported by the tracker.
	 */
	private void serveError(Status status, ErrorMessage.FailureReason reason, RequestHandler requestHandler) throws IOException {
		this.serveError(status, reason.getMessage(), requestHandler);
	}

  public void setAcceptForeignTorrents(boolean acceptForeignTorrents) {
    this.myAcceptForeignTorrents = acceptForeignTorrents;
  }

  public interface RequestHandler {
    void serveResponse(int code, String description, ByteBuffer responseData);
  }
}
