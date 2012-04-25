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
import com.turn.ttorrent.common.protocol.TrackerMessage;
import com.turn.ttorrent.common.protocol.TrackerMessage.*;

import java.net.UnknownHostException;
import java.net.UnknownServiceException;

import java.util.List;
import java.util.HashSet;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Base class for BitTorrent tracker announce threads.
 *
 * <p>
 * A BitTorrent client must check-in to the torrent's tracker to get peers and
 * to report certain events.
 * </p>
 *
 * <p>
 * This Announce class implements a periodic announce request thread that will
 * notify announce request event listeners for each tracker response.
 * </p>
 *
 * @author mpetazzoni
 * @see com.turn.ttorrent.client.announce.Announce.AnnounceEvent
 */
public abstract class Announce implements Runnable, AnnounceResponseListener {

	protected static final Logger logger =
		LoggerFactory.getLogger(Announce.class);

	protected SharedTorrent torrent;
	protected Peer peer;
	private String type;

	/** The set of listeners to announce request answers. */
	private Set<AnnounceResponseListener> listeners;

	/** Announce thread and control. */
	private Thread thread;
	private boolean stop;
	private boolean forceStop;

	/** Announce interval, initial 'started' event control. */
	private int interval;
	private boolean initial;


	/**
	 * Return the appropriate announcer for the given torrent.
	 *
	 * @param torrent The torrent we're announcing about.
	 * @param peer Our peer specification.
	 */
	public static Announce getAnnounce(SharedTorrent torrent, Peer peer)
		throws UnknownHostException, UnknownServiceException {
		String protocol = torrent.getAnnounceUrl().getProtocol();

		if ("http".equals(protocol) || "https".equals(protocol)) {
			return new HTTPAnnounce(torrent, peer);
		} /* else if ("udp".equals(protocol)) {
			return new UDPAnnounce(torrent, peer);
		} */

		throw new UnknownServiceException(
			"Unsupported announce protocol: " + protocol + "!");
	}

	/**
	 * Initialize the base announce class members for the announcer.
	 *
	 * @param torrent The torrent we're announcing about.
	 * @param peer Our peer specification.
	 * @param type A string representing the announce type (used in the thread
	 * name).
	 */
	protected Announce(SharedTorrent torrent, Peer peer, String type) {
		this.torrent = torrent;
		this.peer = peer;
		this.type = type;

		this.listeners = new HashSet<AnnounceResponseListener>();
		this.thread = null;
		this.register(this);

		logger.info("Initialized {} announcer for {} on {}.",
			new Object[] { this.type, this.peer, this.torrent });
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
	 * Start the announce request thread.
	 */
	public void start() {
		this.stop = false;
		this.forceStop = false;

		if (this.thread == null || !this.thread.isAlive()) {
			this.thread = new Thread(this);
			this.thread.setName(String.format("bt-%s-announce", this.type));
			this.thread.start();
		}
	}

	/**
	 * Stop the announce thread.
	 *
	 * <p>
	 * One last 'stopped' announce event might be sent to the tracker to
	 * announce we're going away, depending on the implementation.
	 * </p>
	 */
	public void stop() {
		this.stop = true;

		if (this.thread != null && this.thread.isAlive()) {
			this.thread.interrupt();
			 try {
				this.thread.join();
			 } catch (InterruptedException ie) {
				// Ignore
			 }
		}

		this.thread = null;
	}

	/**
	 * Stop the announce thread.
	 *
	 * @param hard Whether to force stop the announce thread or not, i.e. not
	 * send the final 'stopped' announce request or not.
	 */
	protected void stop(boolean hard) {
		this.forceStop = hard;
		this.stop();
	}

	/**
	 * Main announce loop.
	 *
	 * <p>
	 * The announce thread starts by making the initial 'started' announce
	 * request to register on the tracker and get the announce interval value.
	 * Subsequent announce requests are ordinary, event-less, periodic requests
	 * for peers.
	 * </p>
	 *
	 * <p>
	 * Unless forcefully stopped, the announce thread will terminate by sending
	 * a 'stopped' announce request before stopping.
	 * </p>
	 */
	@Override
	public void run() {
		logger.info("Starting announce loop for " +
				this.torrent.getName() + " to " +
				this.torrent.getAnnounceUrl() + "...");

		// Set an initial announce interval to 5 seconds. This will be updated
		// in real-time by the tracker's responses to our announce requests.
		this.interval = 5;
		this.initial = true;

		while (!this.stop) {
			this.announce(this.initial
				? AnnounceRequestMessage.RequestEvent.STARTED
				: AnnounceRequestMessage.RequestEvent.NONE,
				false);
			this.initial = false;

			try {
				logger.trace("Sending next announce in " + this.interval +
					" seconds.");
				Thread.sleep(this.interval * 1000);
			} catch (InterruptedException ie) {
				// Ignore
			}
		}

		if (!this.forceStop) {
			// Send the final 'stopped' event to the tracker after a little
			// while.
			try {
				Thread.sleep(500);
			} catch (InterruptedException ie) {
				// Ignore
			}

			this.announce(AnnounceRequestMessage.RequestEvent.STOPPED, true);
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
	 * @see #announce(AnnounceEvent event)
	 * @param event The announce event type (can be AnnounceEvent.NONE for
	 * periodic updates).
	 * @param inhibitEvent Prevent event listeners from being notified.
	 */
	public abstract void announce(
		AnnounceRequestMessage.RequestEvent event,
		boolean inhibitEvent);

	/**
	 * Handle the response from the tracker.
	 *
	 * <p>
	 * Analyzes the response from the tracker and acts on it. If the response
	 * is an error, it is logged. Otherwise, the announce response is used
	 * to fire the corresponding announce and peer events to all announce
	 * listeners.
	 * </p>
	 *
	 * @param message The incoming {@link HTTPTrackerMessage}.
	 * @param inhibitEvents Whether or not to prevent events from being fired.
	 */
	protected void handleTrackerResponse(TrackerMessage message,
		boolean inhibitEvents) {
		if (message instanceof ErrorMessage) {
			ErrorMessage error = (ErrorMessage)message;
			logger.warn("Error reported by tracker: {}", error.getReason());
			return;
		}

		if (! (message instanceof AnnounceResponseMessage)) {
			logger.error("Unexpected tracker message type ({})!",
				message.getType().name());
			return;
		}

		if (inhibitEvents) {
			return;
		}

		AnnounceResponseMessage response = (AnnounceResponseMessage)message;
		this.fireAnnounceResponseEvent(
			response.getComplete(),
			response.getIncomplete(),
			response.getInterval());
		this.fireDiscoveredPeersEvent(
			response.getPeers());
	}

	/**
	 * Fire the announce response event to all listeners.
	 *
	 * @param complete The number of seeders on this torrent.
	 * @param incomplete The number of leechers on this torrent.
	 * @param interval The announce interval requested by the tracker.
	 */
	protected void fireAnnounceResponseEvent(int complete, int incomplete,
		int interval) {
		for (AnnounceResponseListener listener : this.listeners) {
			listener.handleAnnounceResponse(complete, incomplete, interval);
		}
	}

	/**
	 * Fire the new peer discovery event to all listeners.
	 *
	 * @param peers The list of peers discovered.
	 */
	protected void fireDiscoveredPeersEvent(List<Peer> peers) {
		for (AnnounceResponseListener listener : this.listeners) {
			listener.handleDiscoveredPeers(peers);
		}
	}

	/** Handle an announce request answer to set the announce interval.
	 *
	 * @param complete The number of seeders on this torrent.
	 * @param incomplete The number of leechers on this torrent.
	 * @param interval The announce interval requested by the tracker.
	 */
	@Override
	public synchronized void handleAnnounceResponse(int complete,
		int incomplete, int interval) {
		if (interval <= 0) {
			this.stop(true);
			return;
		}

		if (this.interval == interval) {
			return;
		}

		logger.info("Setting announce interval to {}s per tracker request.",
			this.interval);
		this.interval = interval;
	}

	/**
	 * Handle the discovery of new peers.
	 *
	 * @param peers The list of peers discovered (from the announce response or
	 * any other means like DHT/PEX, etc.).
	 */
	@Override
	public void handleDiscoveredPeers(List<Peer> peers) {
		// We don't need to do anything with this here.
	}
}
