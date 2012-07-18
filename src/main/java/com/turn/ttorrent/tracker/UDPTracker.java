package com.turn.ttorrent.tracker;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.concurrent.ConcurrentHashMap;

import org.simpleframework.transport.connect.SocketConnection;

/**
 * BitTorrent UDP tracker.
 *
 * <p>
 * The tracker usually listens on port 6969 (the standard BitTorrent tracker
 * port).
 * </p>
 *
 * @author sroze
 */
public class UDPTracker extends Tracker
{
	private UDPTrackerService service;
	
	/**
	 * Create a new BitTorrent tracker listening at the given address on the
	 * default port.
	 *
	 * @param address The address to bind to.
	 * @throws IOException Throws an <em>IOException</em> if the tracker
	 * cannot be initialized.
	 */
	public UDPTracker(InetAddress address) throws IOException {
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
	public UDPTracker(InetSocketAddress address) throws IOException {
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
	public UDPTracker(InetSocketAddress address, String version)
		throws IOException {
		this.address = address;

		this.torrents = new ConcurrentHashMap<String, TrackedTorrent>();
		this.service = new UDPTrackerService(version, this.torrents);
	}

	/**
	 * Start the tracker thread.
	 */
	public void start ()
	{
		if (this.tracker == null || !this.tracker.isAlive()) {
			// Create the socket
			this.tracker = new TrackerThread();
			this.tracker.setName("udp-tracker:" + this.address.getPort());
			this.tracker.start();
		}

		super.start();
	}
	
	/**
	 * Returns the full announce URL served by this tracker.
	 *
	 * <p>
	 * This has the form udp://host:port/.
	 * </p>
	 */
	public URL getAnnounceUrl() {
		try {
			return new URL("udp",
				this.address.getAddress().getCanonicalHostName(),
				this.address.getPort(), "");
		} catch (MalformedURLException mue) {
			logger.error("Could not build tracker URL: {}!", mue, mue);
		}

		return null;
	}
	
	/**
	 * Stop the tracker.
	 *
	 * <p>
	 * This effectively closes the listening HTTP connection to terminate
	 * the service, and interrupts the peer collector thread as well.
	 * </p>
	 */
	public void stop ()
	{
		service.stop();
		logger.info("BitTorrent tracker closed.");

		super.stop();
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
			logger.info("Starting BitTorrent tracker on {}...", getAnnounceUrl());

			try {
				// Start UDP receiver
				DatagramSocket socket = new DatagramSocket(address.getPort());
				service.start(socket);
				
			} catch (IOException ioe) {
				logger.error("Could not start the tracker: {}!", ioe.getMessage());
				UDPTracker.this.stop();
			}
		}
	}
}
