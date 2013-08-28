/**
 * Copyright (C) 2011-2013 Turn, Inc.
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
package com.turn.ttorrent.cli;

import com.turn.ttorrent.tracker.TrackedTorrent;
import com.turn.ttorrent.tracker.Tracker;

import java.io.File;
import java.io.FilenameFilter;
import java.io.PrintStream;
import java.net.InetSocketAddress;

import jargs.gnu.CmdLineParser;
import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.PatternLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Command-line entry-point for starting a {@link Tracker}
 */
public class TrackerMain {

	private static final Logger logger =
		LoggerFactory.getLogger(TrackerMain.class);

	/**
	 * Display program usage on the given {@link PrintStream}.
	 */
	private static void usage(PrintStream s) {
		s.println("usage: Tracker [options] [directory]");
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
			Integer.valueOf(Tracker.DEFAULT_TRACKER_PORT));

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
			Tracker t = new Tracker(new InetSocketAddress(portValue.intValue()));

			File parent = new File(directory);
			for (File f : parent.listFiles(filter)) {
				logger.info("Loading torrent from " + f.getName());
				t.announce(TrackedTorrent.load(f));
			}

			logger.info("Starting tracker with {} announced torrents...",
				t.getTrackedTorrents().size());
			t.start();
		} catch (Exception e) {
			logger.error("{}", e.getMessage(), e);
			System.exit(2);
		}
	}
}
