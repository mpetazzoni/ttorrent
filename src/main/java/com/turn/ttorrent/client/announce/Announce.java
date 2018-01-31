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

import com.turn.ttorrent.client.ClientState;
import com.turn.ttorrent.client.SharedTorrent;
import com.turn.ttorrent.common.Peer;
import com.turn.ttorrent.common.Torrent;
import com.turn.ttorrent.common.TorrentHash;
import com.turn.ttorrent.common.protocol.TrackerMessage.AnnounceRequestMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.UnknownHostException;
import java.net.UnknownServiceException;
import java.util.*;
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

  private List<Peer> myPeers;

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
  private volatile boolean stop;
  private boolean forceStop;

  /**
   * Announce interval.
   */
  private int myAnnounceInterval;
  private TrackerClient myDefaultTracker;

  /**
   * Initialize the base announce class members for the announcer.
   *
   */
  public Announce() {
    this.clients = new ConcurrentHashMap<String, TrackerClient>();
    this.torrents = new CopyOnWriteArrayList<SharedTorrent>();
    this.thread = null;
    myPeers = new CopyOnWriteArrayList<Peer>();
  }

  public void addTorrent(SharedTorrent torrent, AnnounceResponseListener listener) throws UnknownServiceException, UnknownHostException {
    this.torrents.add(torrent);
    URI trackerUrl = torrent.getAnnounce();
    TrackerClient client = this.clients.get(trackerUrl.toString());
    try {
      if (client == null) {
        client = createTrackerClient(myPeers, trackerUrl);
        client.register(listener);
        this.clients.put(trackerUrl.toString(), client);
      }
      if (torrent.getClientState() == ClientState.SEEDING){
        client.announceAllInterfaces(AnnounceRequestMessage.RequestEvent.COMPLETED, true, torrent);
      } else {
        client.announceAllInterfaces(AnnounceRequestMessage.RequestEvent.STARTED, false, torrent);
      }
    } catch (AnnounceException e) {
      logger.info(String.format("Unable to force announce torrent %s on tracker %s.", torrent.getName(), String.valueOf(trackerUrl)));
      logger.debug(String.format("Unable to force announce torrent %s on tracker %s.", torrent.getName(), String.valueOf(trackerUrl)), e );
    }
  }

  public void removeTorrent(TorrentHash torrent) {
    List<SharedTorrent> toRemove = new ArrayList<SharedTorrent>();
    for (SharedTorrent st : this.torrents) {
      if (st.getHexInfoHash().equals(torrent.getHexInfoHash())) {
        toRemove.add(st);
        sendStopEvent(st);
      }
    }
    this.torrents.removeAll(toRemove);
  }

  /**
   * Start the announce request thread.
   */
  public void start(final URI defaultTrackerURI, final AnnounceResponseListener listener, final Peer[] peers, final int announceInterval) {
    myAnnounceInterval = announceInterval;
    myPeers.addAll(Arrays.asList(peers));
    if (defaultTrackerURI != null){
      try {
        myDefaultTracker = createTrackerClient(myPeers, defaultTrackerURI);
        myDefaultTracker.register(listener);
        this.clients.put(defaultTrackerURI.toString(), myDefaultTracker);
      } catch (Exception e) {}
    } else {
      myDefaultTracker = null;
    }

    this.stop = false;
    this.forceStop = false;

    if (this.thread == null || !this.thread.isAlive()) {
      this.thread = new Thread(this);
      this.thread.setName("torrent tracker announce thread");
      this.thread.start();
    }
  }

  /**
   * Set the announce interval.
   */
  public void setAnnounceInterval(int announceInterval) {
    if (announceInterval <= 0) {
      this.stop(true);
      return;
    }

    if (this.myAnnounceInterval == announceInterval) {
      return;
    }

    logger.debug("Setting announce interval to {}s per tracker request.",
            announceInterval);
    this.myAnnounceInterval = announceInterval;
  }

  private void sendStopEvent(SharedTorrent torrent) {
    try {
      final URI uri = torrent.getAnnounce();
      TrackerClient client = this.clients.get(uri.toString());
      client.announceAllInterfaces(AnnounceRequestMessage.RequestEvent.STOPPED, true, torrent);
    } catch (Exception ex) {
      logger.debug("Unable to announce stop on tracker");
    }
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

    Iterator<SharedTorrent> iterator = torrents.iterator();
    while (iterator.hasNext()) {
      sendStopEvent(iterator.next());
    }
    this.torrents.clear();

    this.stop = true;
    this.myPeers.clear();

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


    while (!this.stop && !Thread.currentThread().isInterrupted()) {
      logger.debug("Starting announce for {} torrents", torrents.size());
      for (SharedTorrent torrent : this.torrents) {
        if (this.stop || Thread.currentThread().isInterrupted()){
          break;
        }
        try {
          TrackerClient trackerClient = this.getCurrentTrackerClient(torrent);
          if (trackerClient != null) {
            trackerClient.announceAllInterfaces(AnnounceRequestMessage.RequestEvent.NONE, torrent.isFinished(), torrent);
          } else {
            logger.warn("Tracker client for {} is null. Torrent is not announced on tracker", torrent.getName());
          }
        } catch (Exception e) {
          logger.info(e.getMessage());
          logger.debug(e.getMessage(), e);
        }
      }

      try {
        Thread.sleep(this.myAnnounceInterval * 1000);
      } catch (InterruptedException ie) {
        break;
      }
    }

    logger.info("Exited announce loop.");

    if (!this.forceStop) {
      // Send the final 'stopped' event to the tracker after a little
      // while.
      try {
        Thread.sleep(500);
      } catch (InterruptedException ie) {
        // Ignore
        return;
      }

      try {
        for (SharedTorrent torrent : this.torrents) {
            this.getCurrentTrackerClient(torrent).announceAllInterfaces(AnnounceRequestMessage.RequestEvent.STOPPED, true, torrent);
        }
      } catch (AnnounceException e) {
        logger.info("Can't announce stop: " + e.getMessage());
        logger.debug("Can't announce stop", e);
        // don't try to announce all. Stop after first error, assuming tracker is already unavailable
      }
    }
  }

  /**
   * Create a {@link TrackerClient} annoucing to the given tracker address.
   *
   * @param peers    The list peer the tracker client will announce on behalf of.
   * @param tracker The tracker address as a {@link java.net.URI}.
   * @throws UnknownHostException    If the tracker address is invalid.
   * @throws UnknownServiceException If the tracker protocol is not supported.
   */
  public static TrackerClient createTrackerClient(List<Peer> peers, URI tracker) throws UnknownHostException, UnknownServiceException {
    String scheme = tracker.getScheme();

    if ("http".equals(scheme) || "https".equals(scheme)) {
      return new HTTPTrackerClient(peers, tracker);
    } else if ("udp".equals(scheme)) {
      return new UDPTrackerClient(peers, tracker);
    }

    throw new UnknownServiceException(
      "Unsupported announce scheme: " + scheme + "!");
  }

  /**
   * Returns the current tracker client used for announces.
   */
  public TrackerClient getCurrentTrackerClient(Torrent torrent) {
    List<List<URI>> announceList = torrent.getAnnounceList();
    if (announceList.size() == 0) return null;
    List<URI> uris = announceList.get(0);
    if (uris.size() == 0) return null;
    return this.clients.get(uris.get(0).toString());
  }

  public URI getDefaultTrackerURI(){
    if (myDefaultTracker == null){
      return null;
    }
    return myDefaultTracker.getTrackerURI();
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
