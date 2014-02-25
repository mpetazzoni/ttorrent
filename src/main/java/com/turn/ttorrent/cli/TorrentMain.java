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

import com.turn.ttorrent.common.TorrentCreator;
import java.io.File;
import java.io.OutputStream;
import java.net.URI;
import java.util.ArrayList;

import java.util.Collections;
import java.util.List;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Command-line entry-point for reading and writing {@link Torrent} files.
 */
public class TorrentMain {

    private static final Logger logger = LoggerFactory.getLogger(TorrentMain.class);

    /**
     * Torrent creator.
     *
     * <p>
     * You can use the {@code main()} function of this class to create
     * torrent files. See usage for details.
     * </p>
     */
    public static void main(String[] args) throws Exception {
        // BasicConfigurator.configure(new ConsoleAppender(new PatternLayout("%-5p: %m%n")));

        OptionParser parser = new OptionParser();
        OptionSpec<Void> helpOption = parser.accepts("help")
                .forHelp();
        OptionSpec<File> inputOption = parser.accepts("input")
                .withRequiredArg().ofType(File.class)
                .required()
                .describedAs("The input file or directory for the torrent.");
        OptionSpec<File> outputOption = parser.accepts("output")
                .withRequiredArg().ofType(File.class)
                .required()
                .describedAs("The output torrent file.");
        OptionSpec<URI> announceOption = parser.accepts("announce")
                .withRequiredArg().ofType(URI.class)
                .required()
                .describedAs("The announce URL for the torrent.");
        parser.nonOptions().ofType(File.class)
                .describedAs("Files to include in the torrent.");

        OptionSet options = parser.parse(args);
        List<?> otherArgs = options.nonOptionArguments();

        // Display help and exit if requested
        if (options.has(helpOption)) {
            System.out.println("Usage: Torrent [<options>] <torrent-file>");
            parser.printHelpOn(System.err);
            System.exit(0);
        }

        List<File> files = new ArrayList<File>();
        for (Object o : otherArgs)
            files.add((File) o);
        Collections.sort(files);

        TorrentCreator creator = new TorrentCreator(options.valueOf(inputOption));
        if (!files.isEmpty())
            creator.setFiles(files);
        creator.setAnnounceList(options.valuesOf(announceOption));
        Torrent torrent = creator.create();

        File file = options.valueOf(outputOption);
        OutputStream fos = FileUtils.openOutputStream(file);
        try {
            torrent.save(fos);
        } finally {
            IOUtils.closeQuietly(fos);
        }
    }
}
