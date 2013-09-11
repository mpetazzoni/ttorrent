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

import com.turn.ttorrent.common.Torrent;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.util.Arrays;

import jargs.gnu.CmdLineParser;
import org.apache.commons.io.IOUtils;
import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.PatternLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Command-line entry-point for reading and writing {@link Torrent} files.
 */
public class TorrentMain {

	private static final Logger logger =
		LoggerFactory.getLogger(TorrentMain.class);

	/**
	 * Display program usage on the given {@link PrintStream}.
	 */
	private static void usage(PrintStream s) {
		usage(s, null);
	}

	/**
	 * Display a message and program usage on the given {@link PrintStream}.
	 */
	private static void usage(PrintStream s, String msg) {
		if (msg != null) {
			s.println(msg);
			s.println();
		}

		s.println("usage: Torrent [options] [file|directory]");
		s.println();
		s.println("Available options:");
		s.println("  -h,--help             Show this help and exit.");
		s.println("  -t,--torrent FILE     Use FILE to read/write torrent file.");
		s.println();
		s.println("  -c,--create           Create a new torrent file using " +
			"the given announce URL and data.");
		s.println("  -a,--announce         Tracker URL (can be repeated).");
		s.println();
	}

	/**
	 * Torrent reader and creator.
	 *
	 * <p>
	 * You can use the {@code main()} function of this class to read or create
	 * torrent files. See usage for details.
	 * </p>
	 *
	 * TODO: support multiple announce URLs.
	 */
	public static void main(String[] args) {
		BasicConfigurator.configure(new ConsoleAppender(
			new PatternLayout("%-5p: %m%n")));

		CmdLineParser parser = new CmdLineParser();
		CmdLineParser.Option help = parser.addBooleanOption('h', "help");
		CmdLineParser.Option filename = parser.addStringOption('t', "torrent");
		CmdLineParser.Option create = parser.addBooleanOption('c', "create");
		CmdLineParser.Option announce = parser.addStringOption('a', "announce");

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

		String filenameValue = (String)parser.getOptionValue(filename);
		if (filenameValue == null) {
			usage(System.err, "Torrent file must be provided!");
			System.exit(1);
		}

		Boolean createFlag = (Boolean)parser.getOptionValue(create);
		String announceURL = (String)parser.getOptionValue(announce);

		String[] otherArgs = parser.getRemainingArgs();

		if (Boolean.TRUE.equals(createFlag) &&
			(otherArgs.length != 1 || announceURL == null)) {
			usage(System.err, "Announce URL and a file or directory must be " +
				"provided to create a torrent file!");
			System.exit(1);
		}

		OutputStream fos = null;
		try {
			if (Boolean.TRUE.equals(createFlag)) {
				if (filenameValue != null) {
					fos = new FileOutputStream(filenameValue);
				} else {
					fos = System.out;
				}

				URI announceURI = new URI(announceURL);
				File source = new File(otherArgs[0]);
				if (!source.exists() || !source.canRead()) {
					throw new IllegalArgumentException(
						"Cannot access source file or directory " +
							source.getName());
				}

				String creator = String.format("%s (ttorrent)",
					System.getProperty("user.name"));

				Torrent torrent = null;
				if (source.isDirectory()) {
					File[] files = source.listFiles();
					Arrays.sort(files);
					torrent = Torrent.create(source, Arrays.asList(files),
						announceURI, creator);
				} else {
					torrent = Torrent.create(source, announceURI, creator);
				}

				torrent.save(fos);
			} else {
				Torrent.load(new File(filenameValue), true);
			}
		} catch (Exception e) {
			logger.error("{}", e.getMessage(), e);
			System.exit(2);
		} finally {
			if (fos != System.out) {
				IOUtils.closeQuietly(fos);
			}
		}
	}
}
