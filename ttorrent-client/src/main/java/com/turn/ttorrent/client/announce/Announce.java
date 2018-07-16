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

import com.turn.ttorrent.client.Context;
import com.turn.ttorrent.common.AnnounceableInformation;
import com.turn.ttorrent.common.LoggerUtils;
import com.turn.ttorrent.common.Peer;
import com.turn.ttorrent.common.TorrentLoggerFactory;
import com.turn.ttorrent.common.protocol.AnnounceRequestMessage;
import org.slf4j.Logger;

import java.net.ConnectException;
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
          TorrentLoggerFactory.getLogger();

  private List<Peer> myPeers;
  private final TrackerClientFactory myTrackerClientFactory;

  /**
   * The tiers of tracker clients matching the tracker URIs defined in the
   * torrent.
   */
  private final ConcurrentMap<String, TrackerClient> clients;
  private final Context myContext;

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
   */
  public Announce(Context context, TrackerClientFactory trackerClientFactory) {
    this.clients = new ConcurrentHashMap<String, TrackerClient>();
    this.thread = null;
    myTrackerClientFactory = trackerClientFactory;
    myContext = context;
    myPeers = new CopyOnWriteArrayList<Peer>();
  }

  public void forceAnnounce(AnnounceableInformation torrent, AnnounceResponseListener listener, AnnounceRequestMessage.RequestEvent event) throws UnknownServiceException, UnknownHostException {
    URI trackerUrl = URI.create(torrent.getAnnounce());
    TrackerClient client = this.clients.get(trackerUrl.toString());
    try {
      if (client == null) {
        client = myTrackerClientFactory.createTrackerClient(myPeers, trackerUrl);
        client.register(listener);
        this.clients.put(trackerUrl.toString(), client);
      }
      client.announceAllInterfaces(event, false, torrent);
    } catch (AnnounceException e) {
      logger.info(String.format("Unable to force announce torrent %s on tracker %s.", torrent.getHexInfoHash(), String.valueOf(trackerUrl)));
      logger.debug(String.format("Unable to force announce torrent %s on tracker %s.", torrent.getHexInfoHash(), String.valueOf(trackerUrl)), e);
    }
  }

  /**
   * Start the announce request thread.
   */
  public void start(final URI defaultTrackerURI, final AnnounceResponseListener listener, final Peer[] peers, final int announceInterval) {
    myAnnounceInterval = announceInterval;
    myPeers.addAll(Arrays.asList(peers));
    if (defaultTrackerURI != null) {
      try {
        myDefaultTracker = myTrackerClientFactory.createTrackerClient(myPeers, defaultTrackerURI);
        myDefaultTracker.register(listener);
        this.clients.put(defaultTrackerURI.toString(), myDefaultTracker);
      } catch (Exception e) {
      }
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

    logger.trace("Setting announce interval to {}s per tracker request.",
            announceInterval);
    this.myAnnounceInterval = announceInterval;
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
    this.myPeers.clear();

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

      final List<AnnounceableInformation> announceableInformationList = myContext.getTorrentsStorage().announceableTorrents();
      logger.debug("Starting announce for {} torrents", announceableInformationList.size());
      announceAllTorrents(announceableInformationList, AnnounceRequestMessage.RequestEvent.NONE);
      try {
        Thread.sleep(this.myAnnounceInterval * 1000);
      } catch (InterruptedException ie) {
        break;
      }
    }

    announceAllTorrents(myContext.getTorrentsStorage().announceableTorrents(), AnnounceRequestMessage.RequestEvent.STOPPED);

    logger.info("Exited announce loop.");
  }

  private void defaultAnnounce(List<AnnounceableInformation> torrentsForAnnounce) {
    for (AnnounceableInformation torrent : torrentsForAnnounce) {
      if (this.stop || Thread.currentThread().isInterrupted()) {
        break;
      }
      try {
        TrackerClient trackerClient = this.getCurrentTrackerClient(torrent);
        if (trackerClient != null) {
          trackerClient.announceAllInterfaces(AnnounceRequestMessage.RequestEvent.NONE, false, torrent);
        } else {
          logger.warn("Tracker client for {} is null. Torrent is not announced on tracker", torrent.getHexInfoHash());
        }
      } catch (Exception e) {
        logger.info(e.getMessage());
        logger.debug(e.getMessage(), e);
      }
    }
  }

  private void announceAllTorrents(List<AnnounceableInformation> announceableInformationList, AnnounceRequestMessage.RequestEvent event) {

    logger.debug("Started multi announce. Event {}, torrents {}", event, announceableInformationList);
    final Map<String, List<AnnounceableInformation>> torrentsGroupingByAnnounceUrl = new HashMap<String, List<AnnounceableInformation>>();

    for (AnnounceableInformation torrent : announceableInformationList) {
      final URI uriForTorrent = getURIForTorrent(torrent);
      if (uriForTorrent == null) continue;
      String torrentURI = uriForTorrent.toString();
      List<AnnounceableInformation> sharedTorrents = torrentsGroupingByAnnounceUrl.get(torrentURI);
      if (sharedTorrents == null) {
        sharedTorrents = new ArrayList<AnnounceableInformation>();
        torrentsGroupingByAnnounceUrl.put(torrentURI, sharedTorrents);
      }
      sharedTorrents.add(torrent);
    }

    List<AnnounceableInformation> unannouncedTorrents = new ArrayList<AnnounceableInformation>();
    for (Map.Entry<String, List<AnnounceableInformation>> e : torrentsGroupingByAnnounceUrl.entrySet()) {
      TrackerClient trackerClient = this.clients.get(e.getKey());
      if (trackerClient != null) {
        try {
          trackerClient.multiAnnounce(event, false, e.getValue(), myPeers);
        } catch (AnnounceException t) {
          LoggerUtils.warnAndDebugDetails(logger, "problem in multi announce {}", t.getMessage(), t);
          unannouncedTorrents.addAll(e.getValue());
        } catch (ConnectException t) {
          LoggerUtils.warnWithMessageAndDebugDetails(logger, "Cannot connect to the tracker {}", e.getKey(), t);
          logger.debug("next torrents contain {} in tracker list. {}", e.getKey(), e.getValue());
        }
      } else {
        logger.warn("Tracker client for {} is null. Torrents are not announced on tracker", e.getKey());
        if (e.getKey() == null || e.getKey().isEmpty()) {
          for (AnnounceableInformation announceableInformation : e.getValue()) {
            myContext.getTorrentsStorage().remove(announceableInformation.getHexInfoHash());
          }
        }
      }
    }
    if (unannouncedTorrents.size() > 0) {
      defaultAnnounce(unannouncedTorrents);
    }
  }

  /**
   * Returns the current tracker client used for announces.
   */
  public TrackerClient getCurrentTrackerClient(AnnounceableInformation torrent) {
    final URI uri = getURIForTorrent(torrent);
    if (uri == null) return null;
    return this.clients.get(uri.toString());
  }

  private URI getURIForTorrent(AnnounceableInformation torrent) {
    List<List<String>> announceList = torrent.getAnnounceList();
    if (announceList.size() == 0) return null;
    List<String> uris = announceList.get(0);
    if (uris.size() == 0) return null;
    return URI.create(uris.get(0));
  }

  public URI getDefaultTrackerURI() {
    if (myDefaultTracker == null) {
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
