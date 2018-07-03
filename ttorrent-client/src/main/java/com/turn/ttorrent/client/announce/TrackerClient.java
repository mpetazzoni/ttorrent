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
package com.turn.ttorrent.client.announce;

import com.turn.ttorrent.common.AnnounceableInformation;
import com.turn.ttorrent.common.Peer;
import com.turn.ttorrent.common.TorrentLoggerFactory;
import com.turn.ttorrent.common.protocol.AnnounceRequestMessage;
import com.turn.ttorrent.common.protocol.AnnounceResponseMessage;
import com.turn.ttorrent.common.protocol.TrackerMessage;
import com.turn.ttorrent.common.protocol.TrackerMessage.ErrorMessage;
import org.slf4j.Logger;

import java.net.ConnectException;
import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public abstract class TrackerClient {

  private static final Logger logger =
          TorrentLoggerFactory.getLogger();


  /**
   * The set of listeners to announce request answers.
   */
  private final Set<AnnounceResponseListener> listeners;

  protected final List<Peer> myAddress;
  protected final URI tracker;

  public TrackerClient(final List<Peer> peers, final URI tracker) {
    this.listeners = new HashSet<AnnounceResponseListener>();
    myAddress = peers;
    this.tracker = tracker;
  }

  /**
   * Register a new announce response listener.
   *
   * @param listener The listener to register on this announcer events.
   */
  public void register(AnnounceResponseListener listener) {
    this.listeners.add(listener);
  }

  /**
   * Returns the URI this tracker clients connects to.
   */
  public URI getTrackerURI() {
    return this.tracker;
  }

  public void announceAllInterfaces(final AnnounceRequestMessage.RequestEvent event,
                                    boolean inhibitEvent, final AnnounceableInformation torrent) throws AnnounceException {
    try {
      announce(event, inhibitEvent, torrent, myAddress);
    } catch (AnnounceException e) {
      throw new AnnounceException(String.format("Unable to announce tracker %s event %s for torrent %s and peers %s. Reason %s",
              getTrackerURI(), event.getEventName(), torrent.getHexInfoHash(), Arrays.toString(myAddress.toArray()), e.getMessage()), e);
    }
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
   * @param event        The announce event type (can be AnnounceEvent.NONE for
   *                     periodic updates).
   * @param inhibitEvent Prevent event listeners from being notified.
   * @param torrent
   */
  protected abstract void announce(final AnnounceRequestMessage.RequestEvent event,
                                   boolean inhibitEvent, final AnnounceableInformation torrent, final List<Peer> peer) throws AnnounceException;

  protected abstract void multiAnnounce(final AnnounceRequestMessage.RequestEvent event,
                                        boolean inhibitEvent,
                                        final List<? extends AnnounceableInformation> torrents,
                                        final List<Peer> peer) throws AnnounceException, ConnectException;

  protected void logAnnounceRequest(AnnounceRequestMessage.RequestEvent event, AnnounceableInformation torrent) {
    if (event != AnnounceRequestMessage.RequestEvent.NONE) {
      logger.debug("Announcing {} to tracker with {}U/{}D/{}L bytes...",
              new Object[]{
                      this.formatAnnounceEvent(event),
                      torrent.getUploaded(),
                      torrent.getDownloaded(),
                      torrent.getLeft()
              });
    } else {
      logger.debug("Simply announcing to tracker with {}U/{}D/{}L bytes...",
              new Object[]{
                      torrent.getUploaded(),
                      torrent.getDownloaded(),
                      torrent.getLeft()
              });
    }
  }

  /**
   * Close any opened announce connection.
   *
   * <p>
   * This method is called to make sure all connections
   * are correctly closed when the announce thread is asked to stop.
   * </p>
   */
  protected void close() {
    // Do nothing by default, but can be overloaded.
  }

  /**
   * Formats an announce event into a usable string.
   */
  protected String formatAnnounceEvent(
          AnnounceRequestMessage.RequestEvent event) {
    return AnnounceRequestMessage.RequestEvent.NONE.equals(event)
            ? ""
            : String.format(" %s", event.name());
  }

  /**
   * Handle the announce response from the tracker.
   *
   * <p>
   * Analyzes the response from the tracker and acts on it. If the response
   * is an error, it is logged. Otherwise, the announce response is used
   * to fire the corresponding announce and peer events to all announce
   * listeners.
   * </p>
   *
   * @param message       The incoming {@link TrackerMessage}.
   * @param inhibitEvents Whether or not to prevent events from being fired.
   */
  protected void handleTrackerAnnounceResponse(TrackerMessage message,
                                               boolean inhibitEvents, String hexInfoHash) throws AnnounceException {
    if (message instanceof ErrorMessage) {
      ErrorMessage error = (ErrorMessage) message;
      throw new AnnounceException(error.getReason());
    }

    if (!(message instanceof AnnounceResponseMessage)) {
      throw new AnnounceException("Unexpected tracker message type " +
              message.getType().name() + "!");
    }


    AnnounceResponseMessage response =
            (AnnounceResponseMessage) message;

    this.fireAnnounceResponseEvent(
            response.getComplete(),
            response.getIncomplete(),
            response.getInterval(),
            hexInfoHash);

    if (inhibitEvents) {
      return;
    }

    this.fireDiscoveredPeersEvent(
            response.getPeers(),
            hexInfoHash);
  }

  /**
   * Fire the announce response event to all listeners.
   *
   * @param complete   The number of seeders on this torrent.
   * @param incomplete The number of leechers on this torrent.
   * @param interval   The announce interval requested by the tracker.
   */
  protected void fireAnnounceResponseEvent(int complete, int incomplete, int interval, String hexInfoHash) {
    for (AnnounceResponseListener listener : this.listeners) {
      listener.handleAnnounceResponse(interval, complete, incomplete, hexInfoHash);
    }
  }

  /**
   * Fire the new peer discovery event to all listeners.
   *
   * @param peers The list of peers discovered.
   */
  protected void fireDiscoveredPeersEvent(List<Peer> peers, String hexInfoHash) {
    for (AnnounceResponseListener listener : this.listeners) {
      listener.handleDiscoveredPeers(peers, hexInfoHash);
    }
  }
}
