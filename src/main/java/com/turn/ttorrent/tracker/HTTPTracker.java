package com.turn.ttorrent.tracker;

import jargs.gnu.CmdLineParser;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.PatternLayout;

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
public class HTTPTracker extends Tracker
{
	private final Connection connection;
	
	/** Request path handled by the tracker announce request handler. */
	public static final String ANNOUNCE_URL = "/announce";

	/**
	 * Create a new BitTorrent tracker listening at the given address on the
	 * default port.
	 *
	 * @param address The address to bind to.
	 * @throws IOException Throws an <em>IOException</em> if the tracker
	 * cannot be initialized.
	 */
	public HTTPTracker(InetAddress address) throws IOException {
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
	public HTTPTracker(InetSocketAddress address) throws IOException {
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
	public HTTPTracker(InetSocketAddress address, String version)
		throws IOException {
		this.address = address;

		this.torrents = new ConcurrentHashMap<String, TrackedTorrent>();
		this.connection = new SocketConnection(
				new TrackerService(version, this.torrents));
	}
	
	/**
	 * Start the tracker thread.
	 */
	public void start ()
	{
		if (this.tracker == null || !this.tracker.isAlive()) {
			this.tracker = new TrackerThread();
			this.tracker.setName("http-tracker:" + this.address.getPort());
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
		try {
			this.connection.close();
			logger.info("BitTorrent tracker closed.");
		} catch (IOException ioe) {
			logger.error("Could not stop the tracker: {}!", ioe.getMessage());
		}

		super.stop();
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
				HTTPTracker.ANNOUNCE_URL);
		} catch (MalformedURLException mue) {
			logger.error("Could not build tracker URL: {}!", mue, mue);
		}

		return null;
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
				connection.connect(address);
			} catch (IOException ioe) {
				logger.error("Could not start the tracker: {}!", ioe.getMessage());
				HTTPTracker.this.stop();
			}
		}
	}

	/**
	 * Display program usage on the given {@link PrintStream}.
	 */
	private static void usage(PrintStream s) {
		s.println("usage: HTTPTracker [options] [directory]");
		s.println();
		s.println("Available options:");
		s.println("  -h,--help             Show this help and exit.");
		s.println("  -p,--port PORT        Bind to port PORT.");
		s.println();
	}

	/**
	 * Main function to start a tracker.
	 */
	public static void main(String[] args) {
		BasicConfigurator.configure(new ConsoleAppender(
			new PatternLayout("%d [%-25t] %-5p: %m%n")));

		CmdLineParser parser = new CmdLineParser();
		CmdLineParser.Option help = parser.addBooleanOption('h', "help");
		CmdLineParser.Option port = parser.addIntegerOption('p', "port");

		try {
			parser.parse(args);
		} catch (CmdLineParser.OptionException oe) {
			System.err.println(oe.getMessage());
			usage(System.err);
			System.exit(1);
		}

		// Display help and exit if requested
		if (Boolean.TRUE.equals((Boolean)parser.getOptionValue(help))) {
			usage(System.out);
			System.exit(0);
		}

		Integer portValue = (Integer)parser.getOptionValue(port,
			Integer.valueOf(DEFAULT_TRACKER_PORT));

		String[] otherArgs = parser.getRemainingArgs();

		if (otherArgs.length > 1) {
			usage(System.err);
			System.exit(1);
		}

		// Get directory from command-line argument or default to current
		// directory
		String directory = otherArgs.length > 0
			? otherArgs[0]
			: ".";

		FilenameFilter filter = new FilenameFilter() {
			@Override
			public boolean accept(File dir, String name) {
				return name.endsWith(".torrent");
			}
		};

		try {
			HTTPTracker t = new HTTPTracker(new InetSocketAddress(portValue.intValue()));

			File parent = new File(directory);
			for (File f : parent.listFiles(filter)) {
				logger.info("Loading torrent from " + f.getName());
				t.announce(TrackedTorrent.load(f));
			}

			logger.info("Starting tracker with {} announced torrents...",
				t.torrents.size());
			t.start();
		} catch (Exception e) {
			logger.error("{}", e.getMessage(), e);
			System.exit(2);
		}
	}
}
