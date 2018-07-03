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
import com.turn.ttorrent.common.AnnounceableInformation;
import com.turn.ttorrent.common.LoggerUtils;
import com.turn.ttorrent.common.Peer;
import com.turn.ttorrent.common.TorrentLoggerFactory;
import com.turn.ttorrent.common.protocol.AnnounceRequestMessage;
import com.turn.ttorrent.common.protocol.TrackerMessage.MessageValidationException;
import com.turn.ttorrent.common.protocol.http.HTTPAnnounceRequestMessage;
import com.turn.ttorrent.common.protocol.http.HTTPAnnounceResponseMessage;
import com.turn.ttorrent.common.protocol.http.HTTPTrackerMessage;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Announcer for HTTP trackers.
 *
 * @author mpetazzoni
 * @see <a href="http://wiki.theory.org/BitTorrentSpecification#Tracker_Request_Parameters">BitTorrent tracker request specification</a>
 */
public class HTTPTrackerClient extends TrackerClient {

  protected static final Logger logger =
          TorrentLoggerFactory.getLogger();

  /**
   * Create a new HTTP announcer for the given torrent.
   *
   * @param peers Our own peer specification.
   */
  public HTTPTrackerClient(List<Peer> peers, URI tracker) {
    super(peers, tracker);
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
   * @param event         The announce event type (can be AnnounceEvent.NONE for
   *                      periodic updates).
   * @param inhibitEvents Prevent event listeners from being notified.
   * @param torrentInfo
   */
  public void announce(final AnnounceRequestMessage.RequestEvent event,
                       boolean inhibitEvents, final AnnounceableInformation torrentInfo, final List<Peer> adresses) throws AnnounceException {
    logAnnounceRequest(event, torrentInfo);

    final List<HTTPTrackerMessage> trackerResponses = new ArrayList<HTTPTrackerMessage>();
    for (final Peer address : adresses) {
      final URL target = encodeAnnounceToURL(event, torrentInfo, address);
      try {
        sendAnnounce(target, "GET", new ResponseParser() {
          @Override
          public void parse(InputStream inputStream, int responseCode) throws IOException, MessageValidationException {
            if (responseCode != 200) {
              logger.info("received not http 200 code from tracker for request " + target);
              return;
            }
            trackerResponses.add(HTTPTrackerMessage.parse(inputStream));
          }
        });
      } catch (ConnectException e) {
        throw new AnnounceException(e.getMessage(), e);
      }
    }
    // we process only first request:
    if (trackerResponses.size() > 0) {
      final HTTPTrackerMessage message = trackerResponses.get(0);
      this.handleTrackerAnnounceResponse(message, inhibitEvents, torrentInfo.getHexInfoHash());
    }
  }

  @Override
  protected void multiAnnounce(AnnounceRequestMessage.RequestEvent event,
                               boolean inhibitEvent,
                               final List<? extends AnnounceableInformation> torrents,
                               List<Peer> addresses) throws AnnounceException, ConnectException {
    List<List<HTTPTrackerMessage>> trackerResponses = new ArrayList<List<HTTPTrackerMessage>>();

    URL trackerUrl;
    try {
      trackerUrl = this.tracker.toURL();
    } catch (MalformedURLException e) {
      throw new AnnounceException("Invalid tracker URL " + this.tracker, e);
    }

    for (final Peer address : addresses) {
      StringBuilder body = new StringBuilder();
      for (final AnnounceableInformation torrentInfo : torrents) {
        body.append(encodeAnnounceToURL(event, torrentInfo, address)).append("\n");
      }
      final List<HTTPTrackerMessage> responsesForCurrentIp = new ArrayList<HTTPTrackerMessage>();
      final String bodyStr = body.substring(0, body.length() - 1);
      sendAnnounce(trackerUrl, bodyStr, "POST", new ResponseParser() {
        @Override
        public void parse(InputStream inputStream, int responseCode) throws IOException, MessageValidationException {

          if (responseCode != 200) {
            logger.info("received {} code from tracker for multi announce request.", responseCode);
            logger.debug(bodyStr);
            return;
          }

          final BEValue bdecode = BDecoder.bdecode(inputStream);
          if (bdecode == null) {
            logger.info("tracker sent bad response for multi announce message.");
            logger.debug(bodyStr);
            return;
          }
          final List<BEValue> list = bdecode.getList();
          for (BEValue value : list) {
            responsesForCurrentIp.add(HTTPTrackerMessage.parse(value));
          }
        }
      });
      if (!responsesForCurrentIp.isEmpty()) {
        trackerResponses.add(responsesForCurrentIp);
      }
    }
    // we process only first request:
    if (trackerResponses.size() > 0) {
      final List<HTTPTrackerMessage> messages = trackerResponses.get(0);
      for (HTTPTrackerMessage message : messages) {

        if (!(message instanceof HTTPAnnounceResponseMessage)) {
          logger.info("Incorrect instance of message {}. Skipping...", message);
          continue;
        }

        final String hexInfoHash = ((HTTPAnnounceResponseMessage) message).getHexInfoHash();
        try {
          this.handleTrackerAnnounceResponse(message, inhibitEvent, hexInfoHash);
        } catch (AnnounceException e) {
          LoggerUtils.errorAndDebugDetails(logger, "Unable to process tracker response {}", message, e);
        }
      }
    }
  }

  private URL encodeAnnounceToURL(AnnounceRequestMessage.RequestEvent event, AnnounceableInformation torrentInfo, Peer peer) throws AnnounceException {
    URL result;
    try {
      HTTPAnnounceRequestMessage request = this.buildAnnounceRequest(event, torrentInfo, peer);
      result = request.buildAnnounceURL(this.tracker.toURL());
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
    return result;
  }

  private void sendAnnounce(final URL url, final String method, ResponseParser parser)
          throws AnnounceException, ConnectException {
    sendAnnounce(url, "", method, parser);
  }

  private void sendAnnounce(final URL url, final String body, final String method, ResponseParser parser)
          throws AnnounceException, ConnectException {
    HttpURLConnection conn = null;
    InputStream in = null;
    try {
      conn = (HttpURLConnection) openConnectionCheckRedirects(url, body, method);
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
      throw new ConnectException("No response or unreachable tracker!");
    }

    try {
      parser.parse(in, conn.getResponseCode());
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
        logger.info("Problem ensuring error stream closed!");
        logger.debug("Problem ensuring error stream closed!", ioe);
      }

      // This means trying to close the error stream as well.
      InputStream err = conn.getErrorStream();
      if (err != null) {
        try {
          err.close();
        } catch (IOException ioe) {
          logger.info("Problem ensuring error stream closed!");
          logger.debug("Problem ensuring error stream closed!", ioe);
        }
      }
    }
  }

  private URLConnection openConnectionCheckRedirects(URL url, String body, String method) throws IOException {
    boolean needRedirect;
    int redirects = 0;
    URLConnection connection = url.openConnection();
    boolean firstIteration = true;
    do {
      needRedirect = false;
      connection.setConnectTimeout(10000);
      connection.setReadTimeout(10000);
      HttpURLConnection http = null;
      if (connection instanceof HttpURLConnection) {
        http = (HttpURLConnection) connection;
        http.setInstanceFollowRedirects(false);
      }
      if (http != null) {

        if (firstIteration) {
          firstIteration = false;
          http.setRequestProperty("Content-Type", "text/plain; charset=UTF-8");
          http.setRequestMethod(method);
          if (!body.isEmpty()) {
            connection.setDoOutput(true);
            connection.getOutputStream().write(body.getBytes("UTF-8"));
          }
        }

        int stat = http.getResponseCode();
        if (stat >= 300 && stat <= 307 && stat != 306 &&
                stat != HttpURLConnection.HTTP_NOT_MODIFIED) {
          URL base = http.getURL();
          String newLocation = http.getHeaderField("Location");
          URL target = newLocation == null ? null : new URL(base, newLocation);
          http.disconnect();
          // Redirection should be allowed only for HTTP and HTTPS
          // and should be limited to 5 redirections at most.
          if (redirects >= 5) {
            throw new IOException("too many redirects");
          }
          if (target == null || !(target.getProtocol().equals("http")
                  || target.getProtocol().equals("https"))) {
            throw new IOException("illegal URL redirect or protocol");
          }
          needRedirect = true;
          connection = target.openConnection();
          redirects++;
        }
      }
    }
    while (needRedirect);
    return connection;
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
          AnnounceRequestMessage.RequestEvent event, AnnounceableInformation torrentInfo, Peer peer)
          throws IOException,
          MessageValidationException {
    // Build announce request message
    final long uploaded = torrentInfo.getUploaded();
    final long downloaded = torrentInfo.getDownloaded();
    final long left = torrentInfo.getLeft();
    return HTTPAnnounceRequestMessage.craft(
            torrentInfo.getInfoHash(),
            peer.getPeerIdArray(),
            peer.getPort(),
            uploaded,
            downloaded,
            left,
            true, false, event,
            peer.getIp(),
            AnnounceRequestMessage.DEFAULT_NUM_WANT);
  }

  private interface ResponseParser {

    void parse(InputStream inputStream, int responseCode) throws IOException, MessageValidationException;

  }

}
