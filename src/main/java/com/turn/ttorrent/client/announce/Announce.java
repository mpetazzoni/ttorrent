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

import com.turn.ttorrent.client.SharedTorrent;
import com.turn.ttorrent.common.Peer;
import com.turn.ttorrent.common.TorrentHash;
import com.turn.ttorrent.common.protocol.TrackerMessage.AnnounceRequestMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.UnknownHostException;
import java.net.UnknownServiceException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * BitTorrent announce sub-system.
 * <p/>
 * <p>
 * A BitTorrent client must check-in to the torrent's tracker(s) to get peers
 * and to report certain events.
 * </p>
 * <p/>
 * <p>
 * This Announce class implements a periodic announce request thread that will
 * notify announce request event listeners for each tracker response.
 * </p>
 *
 * @author mpetazzoni
 * @see com.turn.ttorrent.common.protocol.TrackerMessage
 */
public class Announce implements Runnable {

  protected static final Logger logger =
    LoggerFactory.getLogger(Announce.class);

  private final Peer peer;

  /**
   * The tiers of tracker clients matching the tracker URIs defined in the
   * torrent.
   */
  private final ConcurrentMap<String, TrackerClient> clients;
  private final Collection<SharedTorrent> torrents;

  /**
   * Announce thread and control.
   */
  private Thread thread;
  private boolean stop;
  private boolean forceStop;

  /**
   * Announce interval.
   */
  private int interval;

  /**
   * Initialize the base announce class members for the announcer.
   *
   * @param peer Our peer specification.
   */
  public Announce(Peer peer) {
    this.peer = peer;
    this.clients = new ConcurrentHashMap<String, TrackerClient>();
    this.torrents = new CopyOnWriteArrayList<SharedTorrent>();

    this.thread = null;
  }

  public void addTorrent(SharedTorrent torrent, AnnounceResponseListener listener) throws UnknownServiceException, UnknownHostException {
    this.torrents.add(torrent);
    URI trackerUrl = torrent.getAnnounceList().get(0).get(0);
    TrackerClient client = this.clients.get(trackerUrl.toString());
    if (client == null) {
      client = this.createTrackerClient(peer, trackerUrl);
      client.register(listener);
      this.clients.put(trackerUrl.toString(), client);
    }
  }

  public void removeTorrent(TorrentHash torrent) {
    List<SharedTorrent> toRemove = new ArrayList<SharedTorrent>();
    for (SharedTorrent st : this.torrents) {
      if (st.getHexInfoHash().equals(torrent.getHexInfoHash())) {
        toRemove.add(st);
      }
    }
    this.torrents.removeAll(toRemove);
  }

  /**
   * Start the announce request thread.
   */
  public void start() {
    this.stop = false;
    this.forceStop = false;

    if (this.thread == null || !this.thread.isAlive()) {
      this.thread = new Thread(this);
      this.thread.setName("bt-announce(" +
        this.peer.getShortHexPeerId() + ")");
      this.thread.start();
    }
  }

  /**
   * Set the announce interval.
   */
  public void setInterval(int interval) {
    if (interval <= 0) {
      this.stop(true);
      return;
    }

    if (this.interval == interval) {
      return;
    }

    logger.info("Setting announce interval to {}s per tracker request.",
      interval);
    this.interval = interval;
  }

  /**
   * Stop the announce thread.
   * <p/>
   * <p>
   * One last 'stopped' announce event might be sent to the tracker to
   * announce we're going away, depending on the implementation.
   * </p>
   */
  public void stop() {
    this.stop = true;

    if (this.thread != null && this.thread.isAlive()) {
      this.thread.interrupt();

      for (TrackerClient client : this.clients.values()) {
        client.close();
      }

      try {
        this.thread.join();
      } catch (InterruptedException ie) {
        // Ignore
      }
    }

    this.thread = null;
  }

  /**
   * Main announce loop.
   * <p/>
   * <p>
   * The announce thread starts by making the initial 'started' announce
   * request to register on the tracker and get the announce interval value.
   * Subsequent announce requests are ordinary, event-less, periodic requests
   * for peers.
   * </p>
   * <p/>
   * <p>
   * Unless forcefully stopped, the announce thread will terminate by sending
   * a 'stopped' announce request before stopping.
   * </p>
   */
  @Override
  public void run() {
    logger.info("Starting announce loop...");

    // Set an initial announce interval to 5 seconds. This will be updated
    // in real-time by the tracker's responses to our announce requests.
    this.interval = 5;

    AnnounceRequestMessage.RequestEvent event =
      AnnounceRequestMessage.RequestEvent.STARTED;

    while (!this.stop) {
      try {
        logger.debug("Starting announce for {} torrents", torrents.size());
        for (SharedTorrent torrent : this.torrents) {
          TrackerClient trackerClient = this.getCurrentTrackerClient(torrent);
          if (trackerClient != null) {
            trackerClient.announce(event, false, torrent);
          } else {
            logger.warn("Tracker client for {} is null. Torrent is not announced on tracker", torrent.getName());
          }
        }
        event = AnnounceRequestMessage.RequestEvent.NONE;
      } catch (AnnounceException ae) {
        logger.warn(ae.getMessage());
      } catch (Exception e) {
        logger.warn(e.getMessage(), e);
      }

      try {
        Thread.sleep(this.interval * 1000);
      } catch (InterruptedException ie) {
        // Ignore
      }
    }

    logger.info("Exited announce loop.");

    if (!this.forceStop) {
      // Send the final 'stopped' event to the tracker after a little
      // while.
      event = AnnounceRequestMessage.RequestEvent.STOPPED;
      try {
        Thread.sleep(500);
      } catch (InterruptedException ie) {
        // Ignore
      }

      try {
        for (SharedTorrent torrent : this.torrents) {
          this.getCurrentTrackerClient(torrent).announce(event, true, torrent);
        }
      } catch (AnnounceException ae) {
        logger.warn(ae.getMessage());
      }
    }
  }

  /**
   * Create a {@link TrackerClient} annoucing to the given tracker address.
   *
   * @param peer    The peer the tracker client will announce on behalf of.
   * @param tracker The tracker address as a {@link java.net.URI}.
   * @throws UnknownHostException    If the tracker address is invalid.
   * @throws UnknownServiceException If the tracker protocol is not supported.
   */
  private TrackerClient createTrackerClient(Peer peer, URI tracker) throws UnknownHostException, UnknownServiceException {
    String scheme = tracker.getScheme();

    if ("http".equals(scheme) || "https".equals(scheme)) {
      return new HTTPTrackerClient(peer, tracker);
    } else if ("udp".equals(scheme)) {
      return new UDPTrackerClient(peer, tracker);
    }

    throw new UnknownServiceException(
      "Unsupported announce scheme: " + scheme + "!");
  }

  /**
   * Returns the current tracker client used for announces.
   */
  public TrackerClient getCurrentTrackerClient(SharedTorrent torrent) {
    List<List<URI>> announceList = torrent.getAnnounceList();
    if (announceList.size() == 0) return null;
    List<URI> uris = announceList.get(0);
    if (uris.size() == 0) return null;
    return this.clients.get(uris.get(0).toString());
  }

  /**
   * Stop the announce thread.
   *
   * @param hard Whether to force stop the announce thread or not, i.e. not
   *             send the final 'stopped' announce request or not.
   */
  private void stop(boolean hard) {
    this.forceStop = hard;
    this.stop();
  }
}
