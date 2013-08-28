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
import com.turn.ttorrent.common.protocol.TrackerMessage.*;

import java.net.URI;
import java.net.UnknownHostException;
import java.net.UnknownServiceException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * BitTorrent announce sub-system.
 *
 * <p>
 * A BitTorrent client must check-in to the torrent's tracker(s) to get peers
 * and to report certain events.
 * </p>
 *
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

	/** The tiers of tracker clients matching the tracker URIs defined in the
	 * torrent. */
	private final List<List<TrackerClient>> clients;
	private final Set<TrackerClient> allClients;

	/** Announce thread and control. */
	private Thread thread;
	private boolean stop;
	private boolean forceStop;

	/** Announce interval. */
	private int interval;

	private int currentTier;
	private int currentClient;

	/**
	 * Initialize the base announce class members for the announcer.
	 *
	 * @param torrent The torrent we're announcing about.
	 * @param peer Our peer specification.
	 */
	public Announce(SharedTorrent torrent, Peer peer) {
		this.peer = peer;
		this.clients = new ArrayList<List<TrackerClient>>();
		this.allClients = new HashSet<TrackerClient>();

		/**
		 * Build the tiered structure of tracker clients mapping to the
		 * trackers of the torrent.
		 */
		for (List<URI> tier : torrent.getAnnounceList()) {
			ArrayList<TrackerClient> tierClients = new ArrayList<TrackerClient>();
			for (URI tracker : tier) {
				try {
					TrackerClient client = this.createTrackerClient(torrent,
						peer, tracker);

					tierClients.add(client);
					this.allClients.add(client);
				} catch (Exception e) {
					logger.warn("Will not announce on {}: {}!",
						tracker,
						e.getMessage() != null
							? e.getMessage()
							: e.getClass().getSimpleName());
				}
			}

			// Shuffle the list of tracker clients once on creation.
			Collections.shuffle(tierClients);

			// Tier is guaranteed to be non-empty by
			// Torrent#parseAnnounceInformation(), so we can add it safely.
			clients.add(tierClients);
		}

		this.thread = null;
		this.currentTier = 0;
		this.currentClient = 0;

		logger.info("Initialized announce sub-system with {} trackers on {}.",
			new Object[] { torrent.getTrackerCount(), torrent });
	}

	/**
	 * Register a new announce response listener.
	 *
	 * @param listener The listener to register on this announcer events.
	 */
	public void register(AnnounceResponseListener listener) {
		for (TrackerClient client : this.allClients) {
			client.register(listener);
		}
	}

	/**
	 * Start the announce request thread.
	 */
	public void start() {
		this.stop = false;
		this.forceStop = false;

		if (this.clients.size() > 0 && (this.thread == null || !this.thread.isAlive())) {
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

			for (TrackerClient client : this.allClients) {
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
		logger.info("Starting announce loop...");

		// Set an initial announce interval to 5 seconds. This will be updated
		// in real-time by the tracker's responses to our announce requests.
		this.interval = 5;

		AnnounceRequestMessage.RequestEvent event =
			AnnounceRequestMessage.RequestEvent.STARTED;

		while (!this.stop) {
			try {
				this.getCurrentTrackerClient().announce(event, false);
				this.promoteCurrentTrackerClient();
				event = AnnounceRequestMessage.RequestEvent.NONE;
			} catch (AnnounceException ae) {
				logger.warn(ae.getMessage());

				try {
					this.moveToNextTrackerClient();
				} catch (AnnounceException e) {
					logger.error("Unable to move to the next tracker client: {}", e.getMessage());
				}
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
				this.getCurrentTrackerClient().announce(event, true);
			} catch (AnnounceException ae) {
				logger.warn(ae.getMessage());
			}
		}
	}

	/**
	 * Create a {@link TrackerClient} annoucing to the given tracker address.
	 *
	 * @param torrent The torrent the tracker client will be announcing for.
	 * @param peer The peer the tracker client will announce on behalf of.
	 * @param tracker The tracker address as a {@link URI}.
	 * @throws UnknownHostException If the tracker address is invalid.
	 * @throws UnknownServiceException If the tracker protocol is not supported.
	 */
	private TrackerClient createTrackerClient(SharedTorrent torrent, Peer peer,
		URI tracker) throws UnknownHostException, UnknownServiceException {
		String scheme = tracker.getScheme();

		if ("http".equals(scheme) || "https".equals(scheme)) {
			return new HTTPTrackerClient(torrent, peer, tracker);
		} else if ("udp".equals(scheme)) {
			return new UDPTrackerClient(torrent, peer, tracker);
		}

		throw new UnknownServiceException(
			"Unsupported announce scheme: " + scheme + "!");
	}

	/**
	 * Returns the current tracker client used for announces.
	 * @throws AnnounceException When the current announce tier isn't defined
	 *	in the torrent.
	 */
	public TrackerClient getCurrentTrackerClient() throws AnnounceException {
		if ((this.currentTier >= this.clients.size()) ||
			(this.currentClient >= this.clients.get(this.currentTier).size())) {
			throw new AnnounceException("Current tier or client isn't available");
		}

		return this.clients
			.get(this.currentTier)
			.get(this.currentClient);
	}

	/**
	 * Promote the current tracker client to the top of its tier.
	 *
	 * <p>
	 * As defined by BEP#0012, when communication with a tracker is successful,
	 * it should be moved to the front of its tier.
	 * </p>
	 *
	 * <p>
	 * The index of the currently used {@link TrackerClient} is reset to 0 to
	 * reflect this change.
	 * </p>
	 *
	 * @throws AnnounceException
	 */
	private void promoteCurrentTrackerClient() throws AnnounceException {
		logger.trace("Promoting current tracker client for {} " +
			"(tier {}, position {} -> 0).",
			new Object[] {
				this.getCurrentTrackerClient().getTrackerURI(),
				this.currentTier,
				this.currentClient
			});

		Collections.swap(this.clients.get(this.currentTier),
			this.currentClient, 0);
		this.currentClient = 0;
	}

	/**
	 * Move to the next tracker client.
	 *
	 * <p>
	 * If no more trackers are available in the current tier, move to the next
	 * tier. If we were on the last tier, restart from the first tier.
	 * </p>
	 *
	 * <p>
	 * By design no empty tier can be in the tracker list structure so we don't
	 * need to check for empty tiers here.
	 * </p>
	 *
	 * @throws AnnounceException
	 */
	private void moveToNextTrackerClient() throws AnnounceException {
		int tier = this.currentTier;
		int client = this.currentClient + 1;

		if (client >= this.clients.get(tier).size()) {
			client = 0;

			tier++;

			if (tier >= this.clients.size()) {
				tier = 0;
			}
		}

		if (tier != this.currentTier ||
			client != this.currentClient) {
			this.currentTier = tier;
			this.currentClient = client;

			logger.debug("Switched to tracker client for {} " +
				"(tier {}, position {}).",
				new Object[] {
					this.getCurrentTrackerClient().getTrackerURI(),
					this.currentTier,
					this.currentClient
				});
		}
	}

	/**
	 * Stop the announce thread.
	 *
	 * @param hard Whether to force stop the announce thread or not, i.e. not
	 * send the final 'stopped' announce request or not.
	 */
	private void stop(boolean hard) {
		this.forceStop = hard;
		this.stop();
	}
}
