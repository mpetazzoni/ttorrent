/**
 * Copyright (C) 2011-2012 Turn, Inc.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.turn.ttorrent.tracker;

import com.turn.ttorrent.common.*;
import com.turn.ttorrent.common.protocol.AnnounceRequestMessage.RequestEvent;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Tracked torrents are torrent for which we don't expect to have data files
 * for.
 * <p>
 * <p>
 * {@link TrackedTorrent} objects are used by the BitTorrent tracker to
 * represent a torrent that is announced by the tracker. As such, it is not
 * expected to point to any valid local data like. It also contains some
 * additional information used by the tracker to keep track of which peers
 * exchange on it, etc.
 * </p>
 *
 * @author mpetazzoni
 */
public class TrackedTorrent implements TorrentHash {

  private static final Logger logger =
          TorrentLoggerFactory.getLogger();

  /**
   * Minimum announce interval requested from peers, in seconds.
   */
  public static final int MIN_ANNOUNCE_INTERVAL_SECONDS = 5;

  /**
   * Default number of peers included in a tracker response.
   */
  private static final int DEFAULT_ANSWER_NUM_PEERS = 30;

  /**
   * Default announce interval requested from peers, in seconds.
   */
  private static final int DEFAULT_ANNOUNCE_INTERVAL_SECONDS = 10;

  private int answerPeers;
  private int announceInterval;

  private final byte[] info_hash;

  /**
   * Peers currently exchanging on this torrent.
   */
  private ConcurrentMap<PeerUID, TrackedPeer> peers;

  /**
   * Create a new tracked torrent from meta-info binary data.
   *
   * @param info_hash The meta-info byte data.
   *                  encoded and hashed back to create the torrent's SHA-1 hash.
   *                  available.
   */
  public TrackedTorrent(byte[] info_hash) {
    this.info_hash = info_hash;

    this.peers = new ConcurrentHashMap<PeerUID, TrackedPeer>();
    this.answerPeers = TrackedTorrent.DEFAULT_ANSWER_NUM_PEERS;
    this.announceInterval = TrackedTorrent.DEFAULT_ANNOUNCE_INTERVAL_SECONDS;
  }

  /**
   * Returns the map of all peers currently exchanging on this torrent.
   */
  public Map<PeerUID, TrackedPeer> getPeers() {
    return this.peers;
  }

  /**
   * Add a peer exchanging on this torrent.
   *
   * @param peer The new Peer involved with this torrent.
   */
  public void addPeer(TrackedPeer peer) {
    this.peers.put(new PeerUID(peer.getAddress(), this.getHexInfoHash()), peer);
  }

  public TrackedPeer getPeer(PeerUID peerUID) {
    return this.peers.get(peerUID);
  }

  public TrackedPeer removePeer(PeerUID peerUID) {
    return this.peers.remove(peerUID);
  }

  /**
   * Count the number of seeders (peers in the COMPLETED state) on this
   * torrent.
   */
  public int seeders() {
    int count = 0;
    for (TrackedPeer peer : this.peers.values()) {
      if (peer.isCompleted()) {
        count++;
      }
    }
    return count;
  }

  /**
   * Count the number of leechers (non-COMPLETED peers) on this torrent.
   */
  public int leechers() {
    int count = 0;
    for (TrackedPeer peer : this.peers.values()) {
      if (!peer.isCompleted()) {
        count++;
      }
    }
    return count;
  }

  /**
   * Remove unfresh peers from this torrent.
   * <p>
   * <p>
   * Collect and remove all non-fresh peers from this torrent. This is
   * usually called by the periodic peer collector of the BitTorrent tracker.
   * </p>
   */
  public void collectUnfreshPeers(int expireTimeoutSec) {
    for (TrackedPeer peer : this.peers.values()) {
      if (!peer.isFresh(expireTimeoutSec)) {
        this.peers.remove(new PeerUID(peer.getAddress(), this.getHexInfoHash()));
      }
    }
  }

  /**
   * Get the announce interval for this torrent.
   */
  public int getAnnounceInterval() {
    return this.announceInterval;
  }

  /**
   * Set the announce interval for this torrent.
   *
   * @param interval New announce interval, in seconds.
   */
  public void setAnnounceInterval(int interval) {
    if (interval <= 0) {
      throw new IllegalArgumentException("Invalid announce interval");
    }

    this.announceInterval = interval;
  }

  /**
   * Update this torrent's swarm from an announce event.
   * <p>
   * <p>
   * This will automatically create a new peer on a 'started' announce event,
   * and remove the peer on a 'stopped' announce event.
   * </p>
   *
   * @param event      The reported event. If <em>null</em>, means a regular
   *                   interval announce event, as defined in the BitTorrent specification.
   * @param peerId     The byte-encoded peer ID.
   * @param hexPeerId  The hexadecimal representation of the peer's ID.
   * @param ip         The peer's IP address.
   * @param port       The peer's inbound port.
   * @param uploaded   The peer's reported uploaded byte count.
   * @param downloaded The peer's reported downloaded byte count.
   * @param left       The peer's reported left to download byte count.
   * @return The peer that sent us the announce request.
   */
  public TrackedPeer update(RequestEvent event, ByteBuffer peerId,
                            String hexPeerId, String ip, int port, long uploaded, long downloaded,
                            long left) throws UnsupportedEncodingException {
    logger.trace("event {}, Peer: {}:{}", new Object[]{event.getEventName(), ip, port});
    TrackedPeer peer = null;
    TrackedPeer.PeerState state = TrackedPeer.PeerState.UNKNOWN;

    PeerUID peerUID = new PeerUID(new InetSocketAddress(ip, port), getHexInfoHash());
    if (RequestEvent.STARTED.equals(event)) {
      state = TrackedPeer.PeerState.STARTED;
    } else if (RequestEvent.STOPPED.equals(event)) {
      peer = this.removePeer(peerUID);
      state = TrackedPeer.PeerState.STOPPED;
    } else if (RequestEvent.COMPLETED.equals(event)) {
      peer = this.getPeer(peerUID);
      state = TrackedPeer.PeerState.COMPLETED;
    } else if (RequestEvent.NONE.equals(event)) {
      peer = this.getPeer(peerUID);
      state = TrackedPeer.PeerState.STARTED;
    } else {
      throw new IllegalArgumentException("Unexpected announce event type!");
    }

    if (peer == null) {
      peer = new TrackedPeer(this, ip, port, peerId);
      this.addPeer(peer);
    }
    peer.update(state, uploaded, downloaded, left);
    return peer;
  }

  /**
   * Get a list of peers we can return in an announce response for this
   * torrent.
   *
   * @param peer The peer making the request, so we can exclude it from the
   *             list of returned peers.
   * @return A list of peers we can include in an announce response.
   */
  public List<Peer> getSomePeers(Peer peer) {
    List<Peer> peers = new LinkedList<Peer>();

    // Extract answerPeers random peers
    List<TrackedPeer> candidates = new LinkedList<TrackedPeer>(this.peers.values());
    Collections.shuffle(candidates);

    int count = 0;
    for (TrackedPeer candidate : candidates) {
      // Don't include the requesting peer in the answer.
      if (peer != null && peer.looksLike(candidate)) {
        continue;
      }

      // Only serve at most ANSWER_NUM_PEERS peers
      if (count++ > this.answerPeers) {
        break;
      }

      peers.add(candidate);
    }

    return peers;
  }

  /**
   * Load a tracked torrent from the given torrent file.
   *
   * @param torrent The abstract {@link File} object representing the
   *                <tt>.torrent</tt> file to load.
   * @throws IOException              When the torrent file cannot be read.
   */
  public static TrackedTorrent load(File torrent) throws IOException {

    TorrentMetadata torrentMetadata = new TorrentParser().parseFromFile(torrent);
    return new TrackedTorrent(torrentMetadata.getInfoHash());
  }

  @Override
  public byte[] getInfoHash() {
    return this.info_hash;
  }

  @Override
  public String getHexInfoHash() {
    return TorrentUtils.byteArrayToHexString(this.info_hash);
  }

  @Override
  public String toString() {
    return "TrackedTorrent{" +
            "info_hash=" + getHexInfoHash() +
            '}';
  }
}
