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

import com.turn.ttorrent.common.Torrent;

import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.simpleframework.transport.connect.Connection;
import org.simpleframework.transport.connect.SocketConnection;

/**
 * BitTorrent tracker.
 *
 * <p>
 * The tracker usually listens on port 6969 (the standard BitTorrent tracker
 * port). Torrents must be registered directly to this tracker with the
 * {@link #announce(TrackedTorrent torrent)}</code> method.
 * </p>
 *
 * @author mpetazzoni
 */
public class Tracker {

	private static final Logger logger =
		LoggerFactory.getLogger(Tracker.class);

	/** Request path handled by the tracker announce request handler. */
	public static final String ANNOUNCE_URL = "/announce";

	/** Default tracker listening port (BitTorrent's default is 6969). */
	public static final int DEFAULT_TRACKER_PORT = 6969;

	/** Default server name and version announced by the tracker. */
	public static final String DEFAULT_VERSION_STRING =
		"BitTorrent Tracker (ttorrent)";

	private final Connection connection;

	/** The in-memory repository of torrents tracked. */
	private final ConcurrentMap<String, TrackedTorrent> torrents;

	private Thread tracker;
	private Thread collector;
	private boolean stop;
  private String myAnnounceUrl;
  private final int myPort;

  private final TrackerService trackerService;

	/**
	 * Create a new BitTorrent tracker listening at the given address.
	 *
	 * @param version A version string served in the HTTP headers
	 * @throws IOException Throws an <em>IOException</em> if the tracker
	 * cannot be initialized.
	 */
	public Tracker(int port) throws IOException {
      this(port,
          getDefaultAnnounceUrl(new InetSocketAddress(InetAddress.getLocalHost(), port)).toString(),
          null);
    }
	public Tracker(int port, String announceURL) throws IOException {
      this (port, announceURL, null);
    }

	public Tracker(int port, String announceURL, String version)
		throws IOException {
        this.myPort = port;
        this.myAnnounceUrl = announceURL;
		this.torrents = new ConcurrentHashMap<String, TrackedTorrent>();
        this.trackerService = new TrackerService(version, this.torrents);
        this.connection = new SocketConnection(trackerService);
    }

	/**
	 * Returns the full announce URL served by this tracker.
	 *
	 * <p>
	 * This has the form http://host:port/announce.
	 * </p>
	 */
	private static URL getDefaultAnnounceUrl(InetSocketAddress address) {
		try {
			return new URL("http",
				address.getAddress().getCanonicalHostName(),
				address.getPort(),
				ANNOUNCE_URL);
		} catch (MalformedURLException mue) {
			logger.error("Could not build tracker URL: {}!", mue, mue);
		}

		return null;
	}

  public String getAnnounceUrl() {
    return myAnnounceUrl;
  }

  public URI getAnnounceURI() {
    try {
      URL announceURL = new URL(getAnnounceUrl());
      if (announceURL != null) {
        return announceURL.toURI();
      }
    } catch (URISyntaxException e) {
      logger.error("Cannot convert announce URL to URI", e);
    } catch (MalformedURLException e) {
      logger.error("Cannot create URL from announceURL", e);
    }
    return null;
  }

  /**
	 * Start the tracker thread.
	 */
	public void start() {
		if (this.tracker == null || !this.tracker.isAlive()) {
			this.tracker = new TrackerThread();
			this.tracker.setName("tracker:" + myPort);
			this.tracker.start();
		}

		if (this.collector == null || !this.collector.isAlive()) {
			this.collector = new PeerCollectorThread();
			this.collector.setName("peer-collector:" + myPort);
			this.collector.start();
		}
	}

	/**
	 * Stop the tracker.
	 *
	 * <p>
	 * This effectively closes the listening HTTP connection to terminate
	 * the service, and interrupts the peer collector thread as well.
	 * </p>
	 */
	public void stop() {
		this.stop = true;

		try {
			this.connection.close();
			logger.info("BitTorrent tracker closed.");
		} catch (IOException ioe) {
			logger.error("Could not stop the tracker: {}!", ioe.getMessage());
		}

		if (this.collector != null && this.collector.isAlive()) {
			this.collector.interrupt();
            try {
                this.collector.join();
            } catch (InterruptedException e) {
                //
            }
            logger.info("Peer collection terminated.");
        }
        if (this.tracker != null && this.tracker.isAlive()) {
            this.tracker.interrupt();
            try {
                this.tracker.join();
            } catch (InterruptedException e) {
                //
            }
            logger.info("Tracker terminated.");
        }
    }

    /**
	 * Announce a new torrent on this tracker.
	 *
	 * <p>
	 * The fact that torrents must be announced here first makes this tracker a
	 * closed BitTorrent tracker: it will only accept clients for torrents it
	 * knows about, and this list of torrents is managed by the program
	 * instrumenting this Tracker class.
	 * </p>
	 *
	 * @param torrent The Torrent object to start tracking.
	 * @return The torrent object for this torrent on this tracker. This may be
	 * different from the supplied Torrent object if the tracker already
	 * contained a torrent with the same hash.
	 */
	public synchronized TrackedTorrent announce(TrackedTorrent torrent) {
		TrackedTorrent existing = this.torrents.get(torrent.getHexInfoHash());

		if (existing != null) {
			logger.warn("Tracker already announced torrent with hash {}.", existing.getHexInfoHash());
			return existing;
		}

		this.torrents.put(torrent.getHexInfoHash(), torrent);
		logger.info("Registered new torrent with hash {}.", torrent.getHexInfoHash());
		return torrent;
    }

    /**
   * Stop announcing the torrent with given hash.
   * @param info_hash torrent hash
	 */
	public synchronized boolean remove(byte[] info_hash) {
    return info_hash != null &&
           this.torrents.remove(Torrent.byteArrayToHexString(info_hash)) != null;
  }

	/**
	 * Stop announcing the given torrent after a delay.
	 *
   * @param info_hash torrent hash
	 * @param delay The delay, in milliseconds, before removing the torrent.
	 */
	public synchronized void remove(byte[] info_hash, long delay) {
		if (info_hash == null) {
			return;
		}

		new Timer().schedule(new TorrentRemoveTimer(this, info_hash), delay);
	}

	/**
     * Set to true to allow this tracker to track external torrents (i.e. those that were not explicitly announced here).
     * @param acceptForeignTorrents true to accept foreign torrents (false otherwise)
     */
    public void setAcceptForeignTorrents(boolean acceptForeignTorrents) {
        this.trackerService.setAcceptForeignTorrents(acceptForeignTorrents);
    }

	/**
   * @return all tracked torrents.
   */
  public Collection<TrackedTorrent> getTrackedTorrents() {
    return Collections.unmodifiableCollection(this.torrents.values());
  }

  /**
	 * Timer task for removing a torrent from a tracker.
	 *
	 * <p>
	 * This task can be used to stop announcing a torrent after a certain delay
	 * through a Timer.
	 * </p>
	 */
	private static class TorrentRemoveTimer extends TimerTask {

		private Tracker tracker;
		private byte[] info_hash;

		TorrentRemoveTimer(Tracker tracker, byte[] info_hash) {
			this.tracker = tracker;
			this.info_hash = info_hash;
		}

		@Override
		public void run() {
			this.tracker.remove(this.info_hash);
		}
	}

	/**
	 * The main tracker thread.
	 *
	 * <p>
	 * The core of the BitTorrent tracker run by the controller is the
	 * SimpleFramework HTTP service listening on the configured address. It can
	 * be stopped with the <em>stop()</em> method, which closes the listening
	 * socket.
	 * </p>
	 */
	private class TrackerThread extends Thread {

      @Override
      public void run() {
        logger.info("Starting BitTorrent tracker on {}...",
            getAnnounceUrl());

        List<SocketAddress> tries = new ArrayList<SocketAddress>() {{
          try {add(new InetSocketAddress(InetAddress.getByAddress(new byte[4]), myPort));} catch (Exception ex) {}
          try {add(new InetSocketAddress(InetAddress.getLocalHost(), myPort));} catch (Exception ex) {}
          try {add(new InetSocketAddress(InetAddress.getByName(new URL(getAnnounceUrl()).getHost()), myPort));} catch (Exception ex) {}
        }};


        for (SocketAddress address : tries) {
          try {
            if (connection.connect(address) != null) {
              logger.info("Started torrent tracker on {}", address);
              return;
            }
          } catch (IOException ioe) {
            logger.warn("Can't start the tracker using address{} : ", address.toString(), ioe.getMessage());
          }
        }
        logger.error("Cannot start tracker on port {}. Stopping now...", myPort);
        Tracker.this.stop();
      }
    }

	/**
	 * The unfresh peer collector thread.
	 *
	 * <p>
	 * Every PEER_COLLECTION_FREQUENCY_SECONDS, this thread will collect
	 * unfresh peers from all announced torrents.
	 * </p>
	 */
	private class PeerCollectorThread extends Thread {

		private static final int PEER_COLLECTION_FREQUENCY_SECONDS = 15;

		@Override
		public void run() {
			logger.info("Starting tracker peer collection for tracker at {}...",
				getAnnounceUrl());

			while (!stop) {
				for (TrackedTorrent torrent : torrents.values()) {
					torrent.collectUnfreshPeers();
				}

				try {
					Thread.sleep(PeerCollectorThread
							.PEER_COLLECTION_FREQUENCY_SECONDS * 1000);
				} catch (InterruptedException ie) {
					// Ignore
				}
			}
		}
	}
}
