package com.turn.ttorrent.tracker;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentHashMap;

import org.simpleframework.transport.connect.SocketConnection;

public class UDPTracker extends Tracker
{
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
	}

	/**
	 * Start the tracker thread.
	 */
	public void start ()
	{
		if (this.tracker == null || !this.tracker.isAlive()) {
			this.tracker = new TrackerThread();
			this.tracker.setName("udp-tracker:" + this.address.getPort());
			this.tracker.start();
		}

		super.start();
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
		//try {
			// TODO Stop UDP server
			logger.info("BitTorrent tracker closed.");
		//} catch (IOException ioe) {
		//	logger.error("Could not stop the tracker: {}!", ioe.getMessage());
		//}

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
			logger.info("Starting BitTorrent UDP tracker on port {}...", address.getPort());

			//try {
				// TODO Start UDP receiver
			//} catch (IOException ioe) {
			//	logger.error("Could not start the tracker: {}!", ioe.getMessage());
			//	UDPTracker.this.stop();
			//}
		}
	}
}
