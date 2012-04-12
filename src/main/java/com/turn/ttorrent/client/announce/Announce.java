/** Copyright (C) 2011 Turn, Inc.
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

import java.net.InetSocketAddress;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** BitTorrent client tracker announce thread.
 *
 * <p>
 * A BitTorrent client must check-in to the torrent's tracker every now and
 * then, and when particular events happen.
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

	/** The torrent announced by this announce thread. */
	protected SharedTorrent torrent;

	/** The peer ID we report to the tracker. */
	protected String id;

	/** Our client address, to report our IP address and port to the tracker. */
	protected InetSocketAddress address;

	/** The set of listeners to announce request answers. */
	private Set<AnnounceResponseListener> listeners;

	/** Announce thread and control. */
	protected Thread thread;
	protected boolean stop;
	protected boolean forceStop;

	/** Announce interval, initial 'started' event control. */
	protected int interval;
	protected boolean initial;

	/** Announce request event types.
	 *
	 * When the client starts exchanging on a torrent, it must contact the
	 * torrent's tracker with a 'started' announce request, which notifies the
	 * tracker this client now exchanges on this torrent (and thus allows the
	 * tracker to report the existence of this peer to other clients).
	 *
	 * When the client stops exchanging, or when its download completes, it must
	 * also send a specific announce request. Otherwise, the client must send an
	 * eventless (NONE), periodic announce request to the tracker at an
	 * interval specified by the tracker itself, allowing the tracker to
	 * refresh this peer's status and acknowledge that it is still there.
	 */
	protected enum AnnounceEvent {
		NONE,
		STARTED,
		STOPPED,
		COMPLETED;
	};

	/** Create a new announcer for the given torrent.
	 *
	 * @param torrent The torrent we're announcing about.
	 * @param id Our client peer ID.
	 * @param address Our client network address, used to extract our external
	 * IP and listening port.
	 */
	Announce(SharedTorrent torrent, String id, InetSocketAddress address) {
		this.torrent = torrent;
		this.id = id;
		this.address = address;

		this.listeners = new HashSet<AnnounceResponseListener>();
		this.thread = null;
		this.register(this);
	}

	/** Register a new announce response listener.
	 *
	 * @param listener The listener to register on this announcer events.
	 */
	public void register(AnnounceResponseListener listener) {
		this.listeners.add(listener);
	}

	/** Start the announce request thread.
	 */
	public void start() {
		this.stop = false;
		this.forceStop = false;

		if (this.thread == null || !this.thread.isAlive()) {
			this.thread = new Thread(this);
			this.thread.setName("bt-announce");
			this.thread.start();
		}
	}

	/** Stop the announce thread.
	 *
	 * One last 'stopped' announce event will be sent to the tracker to
	 * announce we're going away.
	 */
	public void stop() {
		this.stop = true;

		if (this.thread != null && this.thread.isAlive()) {
			this.thread.interrupt();
		}

		this.thread = null;
	}

	/** Stop the announce thread.
	 *
	 * @param hard Whether to force stop the announce thread or not, i.e. not
	 * send the final 'stopped' announce request or not.
	 */
	public void stop(boolean hard) {
		this.forceStop = hard;
		this.stop();
	}

	@Override
	public abstract void run();

	protected void fireAnnounceResponseEvent(int leechers, int seeders,
		int interval, List<InetSocketAddress> peers) {
		for (AnnounceResponseListener listener : this.listeners) {
			listener.handleAnnounceResponse(-1, -1, interval, peers);
		}
	}

	/** Handle an announce request answer to set the announce interval.
	 */
	public void handleAnnounceResponse(int leechers, int seeders,
		int interval, List<InetSocketAddress> peers) {
		if (interval <= 0) {
			this.stop(true);
			return;
		}

		this.interval = interval;
	}
}
