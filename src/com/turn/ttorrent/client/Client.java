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

package com.turn.ttorrent.client;

import com.turn.ttorrent.bcodec.BEValue;
import com.turn.ttorrent.bcodec.InvalidBEncodingException;
import com.turn.ttorrent.client.peer.PeerActivityListener;
import com.turn.ttorrent.common.Peer;
import com.turn.ttorrent.common.Torrent;
import com.turn.ttorrent.client.peer.SharingPeer;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.text.ParseException;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Observable;
import java.util.Random;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;

/** A pure-java BitTorrent client.
 *
 * <p>
 * A BitTorrent client in its bare essence shares a given torrent. If the
 * torrent is not complete locally, it will continue to download it. If or
 * after the torrent is complete, the client may eventually continue to seed it
 * for other clients.
 * </p>
 *
 * <p>
 * This BitTorrent client implementation is made to be simple to embed and
 * simple to use. First, initialize a ShareTorrent object from a torrent
 * meta-info source (either a file or a byte array, see
 * com.turn.ttorrent.SharedTorrent for how to create a SharedTorrent object).
 * Then, instanciate your Client object with this SharedTorrent and call one of
 * {@link #download} to simply download the torrent, or {@link #share} to
 * download and continue seeding for the given amount of time after the
 * download completes.
 * </p>
 *
 * @author mpetazzoni
 */
public class Client extends Observable implements Runnable,
	   AnnounceResponseListener, IncomingConnectionListener,
		PeerActivityListener {

	private static final Logger logger = Logger.getLogger(Client.class);

	/** Peers unchoking frequency, in seconds. Current BitTorrent specification
	 * recommends 10 seconds to avoid choking fibrilation. */
	private static final int UNCHOKING_FREQUENCY = 7;

	/** Optimistic unchokes are done every 2 loop iterations, i.e. every
	 * 2*UNCHOKING_FREQUENCY seconds. */
	private static final int OPTIMISTIC_UNCHOKE_ITERATIONS = 3;

	private static final int RATE_COMPUTATION_ITERATIONS = 2;
	private static final int MAX_DOWNLOADERS_UNCHOKE = 4;
	private static final int VOLUNTARY_OUTBOUND_CONNECTIONS = 10;

	public enum ClientState {
		WAITING,
		SHARING,
		SEEDING,
		ERROR,
		DONE;
	};

	private static final String BITTORRENT_ID_PREFIX = "-TO0042-";

	private SharedTorrent torrent;
	private ClientState state;

	private String id;
	private String hexId;

	private Thread thread;
	private boolean stop;
	private long seed;

	private InetSocketAddress address;
	private ConnectionHandler service;
	private Announce announce;
	private ConcurrentMap<String, SharingPeer> peers;
	private ConcurrentMap<String, SharingPeer> connected;

	private Random random;

	/** Initialize the BitTorrent client.
	 *
	 * @param address The address to bind to.
	 * @param torrent The torrent to download and share.
	 */
	public Client(InetAddress address, SharedTorrent torrent)
		throws UnknownHostException, IOException {
		this.torrent = torrent;
		this.state = ClientState.WAITING;

		this.id = Client.BITTORRENT_ID_PREFIX + UUID.randomUUID()
			.toString().split("-")[4];
		this.hexId = Torrent.toHexString(this.id);

		// Initialize the incoming connection handler and register ourselves to
		// it.
		this.service = new ConnectionHandler(this.torrent, this.id, address);
		this.service.register(this);
		this.address = this.service.getSocketAddress();

		// Initialize the announce request thread, and register ourselves to it
		// as well.
		this.announce = new Announce(this.torrent, this.id, this.address);
		this.announce.register(this);

		logger.info("BitTorrent client [.." +
				this.hexId.substring(this.hexId.length()-6) +
				"] for " + this.torrent.getName() + " started and " +
				"listening at " + this.address.getAddress().getHostName() +
				":" + this.address.getPort() + "...");

		this.peers = new ConcurrentHashMap<String, SharingPeer>();
		this.connected = new ConcurrentHashMap<String, SharingPeer>();
		this.random = new Random(System.currentTimeMillis());
	}

	/** Get this client's peer ID.
	 */
	public String getID() {
		return this.id;
	}

	/** Return the torrent this client is exchanging on.
	 */
	public SharedTorrent getTorrent() {
		return this.torrent;
	}

	/** Change this client's state and notify its observers.
	 *
	 * If the state has changed, this client's observers will be notified.
	 *
	 * @param state The new client state.
	 */
	private synchronized void setState(ClientState state) {
		if (this.state != state) {
			this.setChanged();
		}
		this.state = state;
		this.notifyObservers(this.state);
	}

	/** Return the current state of this BitTorrent client.
	 */
	public ClientState getState() {
		return this.state;
	}

	/** Download the torrent without seeding after completion.
	 */
	public void download() {
		this.share(0);
	}

	/** Download and share this client's torrent until interrupted.
	 */
	public void share() {
		this.share(-1);
	}

	/** Download and share this client's torrent.
	 *
	 * @param seed Seed time in seconds after the download is complete. Pass
	 * <code>0</code> to immediately stop after downloading.
	 */
	public synchronized void share(int seed) {
		this.seed = seed;
		this.stop = false;

		if (this.thread == null || !this.thread.isAlive()) {
			this.thread = new Thread(this);
			this.thread.setName("bt-client(.." +
					this.hexId.substring(this.hexId.length()-6)
					.toUpperCase() + ")");
			this.thread.start();
		}
	}

	/** Immediately but gracefully stop this client.
	 */
	public synchronized void stop() {
		this.stop = true;

		if (this.thread != null && this.thread.isAlive()) {
			this.thread.interrupt();
		}

		this.thread = null;
	}

	/** Tells whether we are a seed for the torrent we're sharing.
	 */
	public boolean isSeed() {
		return this.torrent.isComplete();
	}

	/** This is the main client loop.
	 *
	 * The main client download loop is very simple: it starts the announce
	 * request thread, the incoming connection handler service, and loops
	 * unchoking peers every UNCHOKING_FREQUENCY seconds until told to stop.
	 * Every OPTIMISTIC_UNCHOKE_ITERATIONS, an optimistic unchoke will be
	 * attempted to try out other peers.
	 *
	 * Once done, it stops the announce and connection services, and returns.
	 */
	@Override
	public void run() {
		// First, analyze the torrent's local data.
		try {
			this.torrent.init();
		} catch (ClosedByInterruptException cbie) {
			logger.warn("Client was interrupted during initialization. " +
					"Aborting right away.");
			this.setState(ClientState.ERROR);
			return;
		} catch (IOException ioe) {
			logger.error("Could not initialize torrent file data!", ioe);
			this.setState(ClientState.ERROR);
			return;
		}

		// Initial completion test
		if (this.torrent.isComplete()) {
			this.seed();
		} else {
			this.setState(ClientState.SHARING);
		}

		this.announce.start();
		this.service.start();

		int optimisticIterations = 0;
		int rateComputationIterations = 0;

		while (!this.stop) {
			optimisticIterations =
				(optimisticIterations == 0 ?
				 Client.OPTIMISTIC_UNCHOKE_ITERATIONS :
				 optimisticIterations - 1);

			rateComputationIterations =
				(rateComputationIterations == 0 ?
				 Client.RATE_COMPUTATION_ITERATIONS :
				 rateComputationIterations - 1);

			try {
				this.unchokePeers(optimisticIterations == 0);
				this.info();
				if (rateComputationIterations == 0) {
					this.resetPeerRates();
				}
			} catch (Exception e) {
				logger.error("An exception occurred during the BitTorrent " +
						"client main loop execution!", e);
			}

			try {
				Thread.sleep(Client.UNCHOKING_FREQUENCY*1000);
			} catch (InterruptedException ie) {
				logger.trace("BitTorrent main loop interrupted.");
			}
		}

		logger.debug("Stopping BitTorrent client connection service " +
				"and announce threads...");
		this.service.stop();
		this.announce.stop();

		// Close all peer connections
		logger.debug("Closing all remaining peer connections...");
		for (SharingPeer peer : this.connected.values()) {
			peer.unbind(true);
		}

		// Determine final state
		if (this.torrent.isComplete()) {
			this.setState(ClientState.DONE);
		} else {
			this.setState(ClientState.ERROR);
		}

		logger.info("BitTorrent client signing off.");
	}

	/** Display information about the BitTorrent client state.
	 *
	 * This emits an information line in the log about this client's state. It
	 * includes the number of choked peers, number of connected peers, number
	 * of known peers, information about the torrent availability and
	 * completion and current transmission rates.
	 */
	public synchronized void info() {
		float dl = 0;
		float ul = 0;
		int choked = 0;
		for (SharingPeer peer : this.connected.values()) {
			dl += peer.getDLRate().get();
			ul += peer.getULRate().get();
			if (peer.isChoked()) {
				choked++;
			}
		}

		logger.info("BitTorrent client " + this.getState().name() + ", " +
			choked + "/" +
			this.connected.size() + "/" +
			this.peers.size() + " peers, " +

			this.torrent.getCompletedPieces().cardinality() + "/" +
			this.torrent.getAvailablePieces().cardinality() + "/" +
			this.torrent.getPieceCount() + " pieces " +
			"(" + String.format("%.2f", this.torrent.getCompletion()) + "%), " +

			String.format("%.2f", dl/1024.0) + "/" +
			String.format("%.2f", ul/1024.0) + " kB/s."
		);
	}

	/** Reset peers download and upload rates.
	 *
	 * This method is called every RATE_COMPUTATION_ITERATIONS to reset the
	 * download and upload rates of all peers. This contributes to making the
	 * download and upload rate computations rolling averages every
	 * UNCHOKING_FREQUENCY * RATE_COMPUTATION_ITERATIONS seconds (usually 20
	 * seconds).
	 */
	private synchronized void resetPeerRates() {
		for (SharingPeer peer : this.connected.values()) {
			peer.getDLRate().reset();
			peer.getULRate().reset();
		}
	}

	/** Retrieve a SharingPeer object from the given peer ID, IP address and
	 * port number.
	 *
	 * This function tries to retrieve an existing peer object based on the
	 * provided peer ID, or IP+Port if no peer ID is known, or otherwise
	 * instantiates a new one and adds it to our peer repository.
	 *
	 * @param peerId The byte-encoded string containing the peer ID. It will be
	 * converted to its hexadecimal representation to lookup the peer in the
	 * repository.
	 * @param ip The peer IP address.
	 * @param port The peer listening port number.
	 */
	private SharingPeer getOrCreatePeer(byte[] peerId, String ip, int port) {
		Peer search = new Peer(ip, port,
				(peerId != null ? ByteBuffer.wrap(peerId) : (ByteBuffer)null));
		SharingPeer peer = null;

		synchronized (this.peers) {
			peer = this.peers.get(search.hasPeerId()
						? search.getHexPeerId()
						: search.getHostIdentifier());

			if (peer != null) {
				return peer;
			}

			if (search.hasPeerId()) {
				peer = this.peers.get(search.getHostIdentifier());
				if (peer != null) {
					// Set peer ID for perviously known peer.
					peer.setPeerId(search.getPeerId());

					this.peers.remove(peer.getHostIdentifier());
					this.peers.putIfAbsent(peer.getHexPeerId(), peer);
					return peer;
				}
			}

			peer = new SharingPeer(ip, port, search.getPeerId(), this.torrent);
			this.peers.putIfAbsent(peer.hasPeerId()
					? peer.getHexPeerId()
					: peer.getHostIdentifier(),
				peer);
			logger.trace("Created new peer " + peer + ".");
		}

		return peer;
	}

	/** Retrieve a peer comparator.
	 *
	 * Returns a peer comparator based on either the download rate or the
	 * upload rate of each peer depending on our state. While sharing, we rely
	 * on the download rate we get from each peer. When our download is
	 * complete and we're only seeding, we use the upload rate instead.
	 *
	 * @return A SharingPeer comparator that can be used to sort peers based on
	 * the download or upload rate we get from them.
	 */
	private Comparator<SharingPeer> getPeerRateComparator() {
		if (ClientState.SHARING.equals(this.state)) {
			return new SharingPeer.DLRateComparator();
		} else if (ClientState.SEEDING.equals(this.state)) {
			return new SharingPeer.ULRateComparator();
		} else {
			throw new IllegalStateException("Client is neither sharing nor " +
					"seeding, we shouldn't be comparing peers at this point.");
		}
	}

	/** Unchoke connected peers.
	 *
	 * This is one of the "clever" places of the BitTorrent client. Every
	 * OPTIMISTIC_UNCHOKING_FREQUENCY seconds, we decide which peers should be
	 * unchocked and authorized to grab pieces from us.
	 *
	 * Reciprocation (tit-for-tat) and upload capping is implemented here by
	 * carefully choosing which peers we unchoke, and which peers we choke.
	 *
	 * The four peers with the best download rate and are interested in us get
	 * unchoked. This maximizes our download rate as we'll be able to get data
	 * from there four "best" peers quickly, while allowing these peers to
	 * download from us and thus reciprocate their generosity.
	 *
	 * Peers that have a better download rate than these four downloaders but
	 * are not interested get unchoked too, we want to be able to download from
	 * them to get more data more quickly. If one becomes interested, it takes
	 * a downloader's place as one of the four top downloaders (i.e. we choke
	 * the downloader with the worst upload rate).
	 *
	 * @param optimistic Whether to perform an optimistic unchoke as well.
	 */
	private synchronized void unchokePeers(boolean optimistic) {
		// Build a set of all connected peers, we don't care about peers we're
		// not connected to.
		TreeSet<SharingPeer> bound = new TreeSet<SharingPeer>(
				this.getPeerRateComparator());
		bound.addAll(this.connected.values());

		if (bound.size() == 0) {
			logger.trace("No connected peers, skipping unchoking.");
			return;
		} else {
			logger.trace("Running unchokePeers() on " + bound.size() +
					" connected peers.");
		}

		int downloaders = 0;
		Set<SharingPeer> choked = new HashSet<SharingPeer>();

		// We're interested in the top downloaders first, so use a descending
		// set.
		for (SharingPeer peer : bound.descendingSet()) {
			if (downloaders < Client.MAX_DOWNLOADERS_UNCHOKE) {
				// Unchoke up to MAX_DOWNLOADERS_UNCHOKE interested peers
				if (peer.isChoking()) {
					if (peer.isInterested()) {
						downloaders++;
					}

					peer.unchoke();
				}
			} else {
				// Choke everybody else
				choked.add(peer);
			}
		}

		// Actually choke all chosen peers (if any), except the eventual
		// optimistic unchoke.
		if (choked.size() > 0) {
			SharingPeer randomPeer = choked.toArray(
					new SharingPeer[0])[this.random.nextInt(choked.size())];

			for (SharingPeer peer : choked) {
				if (optimistic && peer == randomPeer) {
					logger.debug("Optimistic unchoke of " + peer);
					continue;
				}

				peer.choke();
			}
		}
	}


	/** AnnounceResponseListener handler(s). **********************************/

	/** Handle a tracker announce response.
	 *
	 * The torrent's tracker answers each announce request by a response
	 * containing peers exchanging on this torrent. This information is crucial
	 * as it is the base to building our peer swarm.
	 *
	 * @param answer The B-decoded answer map.
	 * @see <a href="http://wiki.theory.org/BitTorrentSpecification#Tracker_Response">BitTorrent tracker response specification</a>
	 */
	@Override
	public void handleAnnounceResponse(Map<String, BEValue> answer) {
		try {
			if (!answer.containsKey("peers")) {
				// No peers returned by the tracker. Apparently we're alone on
				// this one for now.
				return;
			}

			try {
				List<BEValue> peers = answer.get("peers").getList();
				logger.debug("Got tracker response with " + peers.size() +
						" peer(s).");
				for (BEValue peerInfo : peers) {
					Map<String, BEValue> info = peerInfo.getMap();

					try {
						byte[] peerId = info.get("peer id").getBytes();
						String ip = new String(info.get("ip").getBytes(),
								Torrent.BYTE_ENCODING);
						int port = info.get("port").getInt();
						this.processAnnouncedPeer(peerId, ip, port);
					} catch (NullPointerException npe) {
						throw new ParseException("Missing field from peer " +
								"information in tracker response!", 0);
					}
				}
			} catch (InvalidBEncodingException ibee) {
				byte[] data = answer.get("peers").getBytes();
				int nPeers = data.length / 6;
				if (data.length % 6 != 0) {
					throw new InvalidBEncodingException("Invalid peers " +
							"binary information string!");
				}

				ByteBuffer peers = ByteBuffer.wrap(data);
				logger.debug("Got compact tracker response with " + nPeers +
						" peer(s).");

				for (int i=0; i < nPeers ; i++) {
					byte[] ipBytes = new byte[4];
					peers.get(ipBytes);
					String ip = InetAddress.getByAddress(ipBytes)
						.getHostAddress();
					int port = (0xFF & (int)peers.get()) << 8
						| (0xFF & (int)peers.get());
					this.processAnnouncedPeer(null, ip, port);
				}
			}
		} catch (UnknownHostException uhe) {
			logger.warn("Invalid compact tracker response!", uhe);
		} catch (ParseException pe) {
			logger.warn("Invalid tracker response!", pe);
		} catch (InvalidBEncodingException ibee) {
			logger.warn("Invalid tracker response!", ibee);
		} catch (UnsupportedEncodingException uee) {
			logger.error(uee);
		}
	}

	/** Process a peer's information obtained in an announce reply.
	 *
	 * <p>
	 * Retrieve or create a new peer for the peer information obtained, and
	 * eventually connect to it.
	 * </p>
	 *
	 * @param peerId An optional peerId byte array.
	 * @param ip The peer's IP address.
	 * @param port The peer's port.
	 */
	private void processAnnouncedPeer(byte[] peerId, String ip, int port) {
		SharingPeer peer = this.getOrCreatePeer(peerId, ip, port);

		synchronized (peer) {
			// Attempt to connect to the peer if and only if:
			//   - We're not already connected to it;
			//   - We're not a seeder (we leave the responsibility
			//	   of connecting to peers that need to download
			//     something), or we are a seeder but we're still
			//     willing to initiate some outbound connections.
			if (!peer.isBound() &&
				(!this.isSeed() ||
				 this.connected.size() < Client.VOLUNTARY_OUTBOUND_CONNECTIONS)) {
				if (!this.service.connect(peer)) {
					logger.debug("Removing peer " + peer + ".");
					this.peers.remove(peer.hasPeerId()
							? peer.getHexPeerId()
							: peer.getHostIdentifier());
				}
			}
		}
	}


	/** IncomingConnectionListener handler(s). ********************************/

	/** Handle a new peer connection.
	 *
	 * This handler is called once the connection has been successfully
	 * established and the handshake exchange made.
	 *
	 * This generally simply means binding the peer to the socket, which will
	 * put in place the communication thread and logic with this peer.
	 *
	 * @param s The connected socket to the remote peer. Note that if the peer
	 * somehow rejected our handshake reply, this socket might very soon get
	 * closed, but this is handled down the road.
	 * @param peerId The byte-encoded peerId extracted from the peer's
	 * handshake, after validation.
	 * @see com.turn.ttorrent.client.peer.SharingPeer
	 */
	@Override
	public void handleNewPeerConnection(Socket s, byte[] peerId) {
		SharingPeer peer = this.getOrCreatePeer(peerId,
				s.getInetAddress().getHostAddress(), s.getPort());

		try {
			synchronized (peer) {
				peer.register(this);
				peer.bind(s);
			}

			this.connected.put(peer.getHexPeerId(), peer);
			peer.register(this.torrent);
			logger.info("New peer connection with " + peer +
					" [" + this.connected.size() + "/" +
					this.peers.size() + "].");
		} catch (SocketException se) {
			this.connected.remove(peer.getHexPeerId());
			logger.warn("Could not handle new peer connection " +
					"with " + peer + ": " + se.getMessage());
		}
	}


	/** PeerActivityListener handler(s). **************************************/

	@Override
	public void handlePeerChoked(SharingPeer peer) { /* Do nothing */ }

	@Override
	public void handlePeerReady(SharingPeer peer) { /* Do nothing */ }

	@Override
	public void handlePieceAvailability(SharingPeer peer,
			Piece piece) { /* Do nothing */ }

	@Override
	public void handleBitfieldAvailability(SharingPeer peer,
			BitSet availablePieces) { /* Do nothing */ }

	@Override
	public void handlePieceSent(SharingPeer peer,
			Piece piece) { /* Do nothing */ }

	/** Piece download completion handler.
	 *
	 * When a piece is completed, and valid, we announce to all connected peers
	 * that we now have this piece.
	 *
	 * We use this handler to identify when all of the pieces have been
	 * downloaded. When that's the case, we can start the seeding period, if
	 * any.
	 *
	 * @param peer The peer we got the piece from.
	 * @param piece The piece in question.
	 */
	@Override
	public void handlePieceCompleted(SharingPeer peer, Piece piece) {
		synchronized (this.torrent) {
			if (piece.isValid()) {
				// Make sure the piece is marked as completed in the torrent
				// Note: this is required because the order the
				// PeerActivityListeners are called is not defined, and we
				// might be called before the torrent's piece completion
				// handler is.
				this.torrent.markCompleted(piece);
				logger.debug("Completed download of " + piece + ", now has " +
						this.torrent.getCompletedPieces().cardinality() + "/" +
						this.torrent.getPieceCount() + " pieces.");

				// Send a HAVE message to all connected peers
				Message have = Message.HaveMessage.craft(piece.getIndex());
				for (SharingPeer remote : this.connected.values()) {
					remote.send(have);
				}

				// Force notify after each piece is completed to propagate download
				// completion information (or new seeding state)
				this.setChanged();
				this.notifyObservers(this.state);
			}

			if (this.torrent.isComplete()) {
				logger.info("Last piece validated and completed, " +
						"download is complete.");
				this.seed();
			}
		}
	}

	@Override
	public void handlePeerDisconnected(SharingPeer peer) {
		if (this.connected.remove(peer.hasPeerId()
					? peer.getHexPeerId()
					: peer.getHostIdentifier()) != null) {
			logger.debug("Peer " + peer + " disconnected, [" +
					this.connected.size() + "/" +
					this.peers.size() + "].");
		}

		peer.reset();
	}

	@Override
	public void handleIOException(SharingPeer peer, IOException ioe) {
		logger.error("I/O problem occured when reading or writing piece " +
				"data for peer " + peer + ": " + ioe.getMessage());
		this.stop();
		this.setState(ClientState.ERROR);
	}


	/** Post download seeding. ************************************************/

	/** Start the seeding period, if any.
	 *
	 * This method is called when all the pieces of our torrent have been
	 * retrieved. This may happen immediately after the client starts if the
	 * torrent was already fully download or we are the initial seeder client.
	 *
	 * When the download is complete, the client switches to seeding mode for
	 * as long as requested in the <code>share()</code> call, if seeding was
	 * requested. If not, the StopSeedingTask will execute immediately to stop
	 * the client's main loop.
	 *
	 * @see StopSeedingTask
	 */
	private synchronized void seed() {
		// Silently ignore if we're already seeding.
		if (ClientState.SEEDING.equals(this.getState())) {
			return;
		}

		logger.info("Download of " + this.torrent.getPieceCount() +
				" pieces completed.");

		if (this.seed == 0) {
			logger.info("No seeding requested, stopping client...");
			this.stop();
			return;
		}

		this.setState(ClientState.SEEDING);
		if (this.seed < 0) {
			logger.info("Seeding indefinetely...");
			return;
		}

		logger.info("Seeding for " + this.seed + " seconds...");
		Timer seedTimer = new Timer();
		seedTimer.schedule(new StopSeedingTask(this), this.seed*1000);
	}

	/** Timer task to stop seeding.
	 *
	 * This TimerTask will be called by a timer set after the download is
	 * complete to stop seeding from this client after a certain amount of
	 * requested seed time (might be 0 for immediate termination).
	 *
	 * This task simply contains a reference to this client instance and calls
	 * its <code>stop()</code> method to interrupt the client's main loop.
	 *
	 * @author mpetazzoni
	 */
	private class StopSeedingTask extends TimerTask {

		private Client client;

		StopSeedingTask(Client client) {
			this.client = client;
		}

		@Override
		public void run() {
			this.client.stop();
		}
	};


	/** Main client entry point for standalone operation.
	 */
	public static void main(String[] args) {
		BasicConfigurator.configure(new ConsoleAppender(
					new PatternLayout("%d [%-20t] %-5p: %m%n")));

		if (args.length < 1) {
			System.err.println("usage: Client <torrent> [directory]");
			System.exit(1);
		}

		try {
			Client c = new Client(
					InetAddress.getByName(System.getenv("HOSTNAME")),
					SharedTorrent.fromFile(
					new File(args[0]),
					new File(args.length > 1 ? args[1] : "/tmp")));
			c.share();
			if (ClientState.ERROR.equals(c.getState())) {
				System.exit(1);
			}
		} catch (Exception e) {
			logger.fatal(e);
			e.printStackTrace();
			System.exit(2);
		}
	}
}
