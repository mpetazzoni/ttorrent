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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collection;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.simpleframework.transport.connect.Connection;
import org.simpleframework.transport.connect.SocketConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * BitTorrent tracker.
 *
 * <p>
 * The tracker usually listens on port 6969 (the standard BitTorrent tracker
 * port). Torrents must be registered directly to this tracker with the {@link
 * #announce(TrackedTorrent torrent)} method.
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
	private final InetSocketAddress address;

	/** The in-memory repository of torrents tracked. */
	private final ConcurrentMap<String, TrackedTorrent> torrents;

	private Thread tracker;
	private Thread collector;
	private boolean stop;

	/**
	 * Create a new BitTorrent tracker listening at the given address on the
	 * default port.
	 *
	 * @param address The address to bind to.
	 * @throws IOException Throws an <em>IOException</em> if the tracker
	 * cannot be initialized.
	 */
	public Tracker(InetAddress address) throws IOException {
		this(new InetSocketAddress(address, DEFAULT_TRACKER_PORT),
			DEFAULT_VERSION_STRING);
	}

	/**
	 * Create a new BitTorrent tracker listening at the given address.
	 *
	 * @param address The address to bind to.
	 * @throws IOException Throws an <em>IOException</em> if the tracker
	 * cannot be initialized.
	 */
	public Tracker(InetSocketAddress address) throws IOException {
		this(address, DEFAULT_VERSION_STRING);
	}

	/**
	 * Create a new BitTorrent tracker listening at the given address.
	 *
	 * @param address The address to bind to.
	 * @param version A version string served in the HTTP headers
	 * @throws IOException Throws an <em>IOException</em> if the tracker
	 * cannot be initialized.
	 */
	public Tracker(InetSocketAddress address, String version)
		throws IOException {
		this.address = address;

		this.torrents = new ConcurrentHashMap<String, TrackedTorrent>();
		this.connection = new SocketConnection(
				new TrackerService(version, this.torrents));
	}

	/**
	 * Returns the full announce URL served by this tracker.
	 *
	 * <p>
	 * This has the form http://host:port/announce.
	 * </p>
	 */
	public URL getAnnounceUrl() {
		try {
			return new URL("http",
				this.address.getAddress().getCanonicalHostName(),
				this.address.getPort(),
				Tracker.ANNOUNCE_URL);
		} catch (MalformedURLException mue) {
			logger.error("Could not build tracker URL: {}!", mue, mue);
		}

		return null;
	}

	/**
	 * Start the tracker thread.
	 */
	public void start() {
		if (this.tracker == null || !this.tracker.isAlive()) {
			this.tracker = new TrackerThread();
			this.tracker.setName("tracker:" + this.address.getPort());
			this.tracker.start();
		}

		if (this.collector == null || !this.collector.isAlive()) {
			this.collector = new PeerCollectorThread();
			this.collector.setName("peer-collector:" + this.address.getPort());
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
			logger.info("Peer collection terminated.");
		}
	}

	/**
	 * Returns the list of tracker's torrents
	 */
	public Collection<TrackedTorrent> getTrackedTorrents() {
		return torrents.values();
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
			logger.warn("Tracker already announced torrent for '{}' " +
				"with hash {}.", existing.getName(), existing.getHexInfoHash());
			return existing;
		}

		this.torrents.put(torrent.getHexInfoHash(), torrent);
		logger.info("Registered new torrent for '{}' with hash {}.",
			torrent.getName(), torrent.getHexInfoHash());
		return torrent;
	}

	/**
	 * Stop announcing the given torrent.
	 *
	 * @param torrent The Torrent object to stop tracking.
	 */
	public synchronized void remove(Torrent torrent) {
		if (torrent == null) {
			return;
		}

		this.torrents.remove(torrent.getHexInfoHash());
	}

	/**
	 * Stop announcing the given torrent after a delay.
	 *
	 * @param torrent The Torrent object to stop tracking.
	 * @param delay The delay, in milliseconds, before removing the torrent.
	 */
	public synchronized void remove(Torrent torrent, long delay) {
		if (torrent == null) {
			return;
		}

		new Timer().schedule(new TorrentRemoveTimer(this, torrent), delay);
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
		private Torrent torrent;

		TorrentRemoveTimer(Tracker tracker, Torrent torrent) {
			this.tracker = tracker;
			this.torrent = torrent;
		}

		@Override
		public void run() {
			this.tracker.remove(torrent);
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

			try {
				connection.connect(address);
			} catch (IOException ioe) {
				logger.error("Could not start the tracker: {}!", ioe.getMessage());
				Tracker.this.stop();
			}
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
