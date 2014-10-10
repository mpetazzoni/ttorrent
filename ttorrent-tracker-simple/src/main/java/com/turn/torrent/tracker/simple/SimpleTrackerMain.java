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
package com.turn.torrent.tracker.simple;

import com.codahale.metrics.JmxReporter;
import com.turn.ttorrent.protocol.torrent.Torrent;
import com.turn.ttorrent.tracker.TrackerUtils;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;

import java.net.URISyntaxException;
import java.util.Arrays;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Command-line entry-point for starting a {@link SimpleTracker}
 */
public class SimpleTrackerMain {

    private static final Logger logger = LoggerFactory.getLogger(SimpleTrackerMain.class);

    private static void addAnnounce(SimpleTracker tracker, File file, int depth) throws IOException, URISyntaxException {
        if (file.isFile()) {
            logger.info("Loading torrent from " + file.getName());
            if (file.getName().endsWith(".torrent"))
                tracker.addTorrent(new Torrent(file));
            return;
        }
        if (depth > 3)
            return;
        for (File child : file.listFiles())
            addAnnounce(tracker, child, depth + 1);
    }

    /**
     * Main function to start a tracker.
     */
    public static void main(String[] args) throws Exception {
        // BasicConfigurator.configure(new ConsoleAppender(new PatternLayout("%d [%-25t] %-5p: %m%n")));

        OptionParser parser = new OptionParser();
        OptionSpec<Void> helpOption = parser.accepts("help")
                .forHelp();
        OptionSpec<File> fileOption = parser.acceptsAll(Arrays.asList("file", "directory"))
                .withRequiredArg().ofType(File.class)
                .required()
                .describedAs("The list of torrent directories or files to announce.");
        OptionSpec<Integer> portOption = parser.accepts("port")
                .withRequiredArg().ofType(Integer.class)
                .defaultsTo(TrackerUtils.DEFAULT_TRACKER_PORT)
                .required()
                .describedAs("The port to listen on.");
        parser.nonOptions().ofType(File.class);

        OptionSet options = parser.parse(args);
        // List<?> otherArgs = options.nonOptionArguments();

        // Display help and exit if requested
        if (options.has(helpOption)) {
            System.out.println("Usage: " + SimpleTrackerMain.class.getSimpleName() + " [<options>]");
            parser.printHelpOn(System.err);
            System.exit(0);
        }

        InetSocketAddress address = new InetSocketAddress(options.valueOf(portOption));
        SimpleTracker t = new SimpleTracker(address);
        JmxReporter reporter = JmxReporter.forRegistry(t.getMetricRegistry()).build();

        try {
            for (File file : options.valuesOf(fileOption))
                addAnnounce(t, file, 0);
            logger.info("Starting tracker with {} announced torrents...", t.getTorrents().size());
            t.start();
            reporter.start();
        } catch (Exception e) {
            logger.error("{}", e.getMessage(), e);
            System.exit(2);
        } finally {
            t.stop();
        }
    }
}
