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
package com.turn.ttorrent.client.main;

import com.turn.ttorrent.client.Client;

import com.turn.ttorrent.protocol.torrent.Torrent;
import java.io.File;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.channels.UnsupportedAddressTypeException;
import java.util.Enumeration;

import java.util.List;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Command-line entry-point for starting a {@link Client}
 */
public class ClientMain {

    private static final Logger logger = LoggerFactory.getLogger(ClientMain.class);
    /**
     * Default data output directory.
     */
    private static final String DEFAULT_OUTPUT_DIRECTORY = "/tmp";

    /**
     * Returns a usable {@link Inet4Address} for the given interface name.
     *
     * <p>
     * If an interface name is given, return the first usable IPv4 address for
     * that interface. If no interface name is given or if that interface
     * doesn't have an IPv4 address, return's localhost address (if IPv4).
     * </p>
     *
     * <p>
     * It is understood this makes the client IPv4 only, but it is important to
     * remember that most BitTorrent extensions (like compact peer lists from
     * trackers and UDP tracker support) are IPv4-only anyway.
     * </p>
     *
     * @param iface The network interface name.
     * @return A usable IPv4 address as a {@link Inet4Address}.
     * @throws UnsupportedAddressTypeException If no IPv4 address was available
     * to bind on.
     */
    private static Inet4Address getIPv4Address(String iface)
            throws SocketException, UnsupportedAddressTypeException,
            UnknownHostException {
        if (iface != null) {
            Enumeration<InetAddress> addresses =
                    NetworkInterface.getByName(iface).getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress addr = addresses.nextElement();
                if (addr instanceof Inet4Address) {
                    return (Inet4Address) addr;
                }
            }
        }

        InetAddress localhost = InetAddress.getLocalHost();
        if (localhost instanceof Inet4Address) {
            return (Inet4Address) localhost;
        }

        throw new UnsupportedAddressTypeException();
    }

    /**
     * Main client entry point for stand-alone operation.
     */
    public static void main(String[] args) throws Exception {
        // BasicConfigurator.configure(new ConsoleAppender(new PatternLayout("%d [%-25t] %-5p: %m%n")));

        OptionParser parser = new OptionParser();
        OptionSpec<Void> helpOption = parser.accepts("help")
                .forHelp();
        OptionSpec<File> outputOption = parser.accepts("output")
                .withRequiredArg().ofType(File.class)
                .defaultsTo(new File(DEFAULT_OUTPUT_DIRECTORY));
        OptionSpec<String> ifaceOption = parser.accepts("iface")
                .withRequiredArg();
        OptionSpec<Integer> seedOption = parser.accepts("seed")
                .withRequiredArg().ofType(Integer.class)
                .defaultsTo(-1);
        OptionSpec<Double> uploadOption = parser.accepts("max-upload")
                .withRequiredArg().ofType(Double.class)
                .defaultsTo(0d);
        OptionSpec<Double> downloadOption = parser.accepts("max-download")
                .withRequiredArg().ofType(Double.class)
                .defaultsTo(0d);
        OptionSpec<File> torrentOption = parser.nonOptions()
                .ofType(File.class);

        OptionSet options = parser.parse(args);
        List<?> otherArgs = options.nonOptionArguments();

        // Display help and exit if requested
        if (options.has(helpOption) || otherArgs.size() != 1) {
            System.out.println("Usage: Client [<options>] <torrent-file>");
            parser.printHelpOn(System.err);
            System.exit(0);
        }

        File outputValue = options.valueOf(outputOption);

        InetAddress address = getIPv4Address(options.valueOf(ifaceOption));
        // TODO: Pass this through to PeerServer and PeerClient.
        Client c = new Client(null);
        try {
            c.start();

            // c.setMaxDownloadRate(options.valueOf(downloadOption));
            // c.setMaxUploadRate(options.valueOf(uploadOption));

            // Set a shutdown hook that will stop the sharing/seeding and send
            // a STOPPED announce request.
            // Runtime.getRuntime().addShutdownHook(new Thread(new Client.ClientShutdown(c, null)));

            for (File file : options.valuesOf(torrentOption)) {
                Torrent torrent = new Torrent(file);
                c.addTorrent(torrent, options.valueOf(outputOption));
            }

        } catch (Exception e) {
            logger.error("Fatal error: {}", e.getMessage(), e);
            System.exit(2);
        } finally {
            c.stop();
        }
    }
}
