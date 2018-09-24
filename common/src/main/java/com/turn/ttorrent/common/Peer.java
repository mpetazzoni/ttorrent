/**
 * Copyright (C) 2011-2012 Turn, Inc.
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
package com.turn.ttorrent.common;

import com.turn.ttorrent.Constants;
import org.slf4j.Logger;

import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;


/**
 * A basic BitTorrent peer.
 *
 * <p>
 * This class is meant to be a common base for the tracker and client, which
 * would presumably subclass it to extend its functionality and fields.
 * </p>
 *
 * @author mpetazzoni
 */
public class Peer {

  private static final Logger logger = TorrentLoggerFactory.getLogger(Peer.class);

  private final InetSocketAddress address;
  private final String hostId;

  private ByteBuffer peerId;
  private volatile String hexPeerId;
  private volatile String hexInfoHash;

  /**
   * Instantiate a new peer.
   *
   * @param address The peer's address, with port.
   */
  public Peer(InetSocketAddress address) {
    this(address, null);
  }

  /**
   * Instantiate a new peer.
   *
   * @param ip   The peer's IP address.
   * @param port The peer's port.
   */
  public Peer(String ip, int port) {
    this(new InetSocketAddress(ip, port), null);
  }

  /**
   * Instantiate a new peer.
   *
   * @param ip     The peer's IP address.
   * @param port   The peer's port.
   * @param peerId The byte-encoded peer ID.
   */
  public Peer(String ip, int port, ByteBuffer peerId) {
    this(new InetSocketAddress(ip, port), peerId);
  }

  /**
   * Instantiate a new peer.
   *
   * @param address The peer's address, with port.
   * @param peerId  The byte-encoded peer ID.
   */
  public Peer(InetSocketAddress address, ByteBuffer peerId) {
    this.address = address;
    this.hostId = String.format("%s:%d",
            this.address.getAddress(),
            this.address.getPort());

    this.setPeerId(peerId);
  }

  /**
   * Tells whether this peer has a known peer ID yet or not.
   */
  public boolean hasPeerId() {
    return this.peerId != null;
  }

  /**
   * Returns the raw peer ID as a {@link ByteBuffer}.
   */
  public ByteBuffer getPeerId() {
    return this.peerId;
  }

  public byte[] getPeerIdArray() {
    return peerId == null ? null : peerId.array();
  }

  /**
   * Set a peer ID for this peer (usually during handshake).
   *
   * @param peerId The new peer ID for this peer.
   */
  public void setPeerId(ByteBuffer peerId) {
    if (peerId != null) {
      this.peerId = peerId;
      this.hexPeerId = TorrentUtils.byteArrayToHexString(peerId.array());
    } else {
      this.peerId = null;
      this.hexPeerId = null;
    }
  }

  public String getStringPeerId() {
    try {
      return new String(peerId.array(), Constants.BYTE_ENCODING);
    } catch (UnsupportedEncodingException e) {
      LoggerUtils.warnAndDebugDetails(logger, "can not get peer id as string", e);
    }
    return null;
  }

  /**
   * Get the hexadecimal-encoded string representation of this peer's ID.
   */
  public String getHexPeerId() {
    return this.hexPeerId;
  }

  /**
   * Get the shortened hexadecimal-encoded peer ID.
   */
  public String getShortHexPeerId() {
    return String.format("..%s",
            this.hexPeerId.substring(this.hexPeerId.length() - 6).toUpperCase());
  }

  /**
   * Returns this peer's IP address.
   */
  public String getIp() {
    return this.address.getAddress().getHostAddress();
  }

  /**
   * Returns this peer's InetAddress.
   */
  public InetSocketAddress getAddress() {
    return this.address;
  }

  /**
   * Returns this peer's port number.
   */
  public int getPort() {
    return this.address.getPort();
  }

  /**
   * Returns this peer's host identifier ("host:port").
   */
  public String getHostIdentifier() {
    return this.hostId;
  }

  /**
   * Returns a binary representation of the peer's IP.
   */
  public byte[] getRawIp() {
    final InetAddress address = this.address.getAddress();
    if (address == null) return null;
    return address.getAddress();
  }


  /**
   * Tells if two peers seem to look alike (i.e. they have the same IP, port
   * and peer ID if they have one).
   */
  public boolean looksLike(Peer other) {
    if (other == null) {
      return false;
    }

    return this.hostId.equals(other.hostId) && this.getPort() == other.getPort();
  }

  public void setTorrentHash(String hexInfoHash) {
    this.hexInfoHash = hexInfoHash;
  }

  public String getHexInfoHash() {
    return hexInfoHash;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    Peer peer = (Peer) o;

    if (hexPeerId == null && peer.hexPeerId == null) return super.equals(o);

    if (hexPeerId != null ? !hexPeerId.equals(peer.hexPeerId) : peer.hexPeerId != null) return false;
    return hexInfoHash != null ? hexInfoHash.equals(peer.hexInfoHash) : peer.hexInfoHash == null;
  }

  @Override
  public int hashCode() {

    if (hexPeerId == null) return super.hashCode();

    int result = hexPeerId != null ? hexPeerId.hashCode() : 0;
    result = 31 * result + (hexInfoHash != null ? hexInfoHash.hashCode() : 0);
    return result;
  }

  /**
   * Returns a human-readable representation of this peer.
   */
  @Override
  public String toString() {
    return "Peer " + address + " for torrent " + hexInfoHash;
  }
}
