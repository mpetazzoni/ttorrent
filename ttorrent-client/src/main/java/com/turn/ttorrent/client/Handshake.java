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
package com.turn.ttorrent.client;

import com.turn.ttorrent.Constants;
import com.turn.ttorrent.common.TorrentHash;
import com.turn.ttorrent.common.TorrentUtils;

import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.text.ParseException;


/**
 * Peer handshake handler.
 *
 * @author mpetazzoni
 * @comments rpilyushin
 *
 */

/**
 * Represents a BitTorrent handshake message.
 * This class encapsulates the structure and parsing logic for the handshake
 * that is exchanged between peers when establishing a connection in the
 * BitTorrent protocol.
 */

public class Handshake implements TorrentHash {

  // BitTorrent protocol identifier as specified by the BitTorrent specification.
  public static final String BITTORRENT_PROTOCOL_IDENTIFIER = "BitTorrent protocol";
  // Base length for a handshake message without the protocol identifier.
  public static final int BASE_HANDSHAKE_LENGTH = 49;

  // ByteBuffer to store the full handshake data.
  private ByteBuffer data;
  // ByteBuffer to store the torrent info hash.
  private ByteBuffer infoHash;
  // ByteBuffer to store the peer ID.
  private ByteBuffer peerId;

  // String to store an identifier for the torrent, not used in the actual handshake message.
  private String torrentIdentifier;

  // The length of the protocol identifier string in this handshake.
  private int myPstrlen;

  // Private constructor for internal use to set up the handshake object.
  private Handshake(ByteBuffer data, ByteBuffer infoHash,
                    ByteBuffer peerId) {
    this.data = data;
    this.data.rewind(); // Rewind the buffer to the start for reading.

    this.infoHash = infoHash;
    this.peerId = peerId;
  }

  // Returns the raw handshake data as a ByteBuffer.
  public ByteBuffer getData() {
    return this.data;
  }

  // Returns the info hash as a byte array.
  public byte[] getInfoHash() {
    return this.infoHash.array();
  }

  // Returns a hexadecimal string representation of the info hash.
  public String getHexInfoHash() {
    return TorrentUtils.byteArrayToHexString(getInfoHash());
  }

  // Returns the peer ID as a byte array.
  public byte[] getPeerId() {
    return this.peerId.array();
  }

  // Parses a ByteBuffer into a Handshake object, validating the structure of the handshake.
  public static Handshake parse(ByteBuffer buffer)
          throws ParseException, UnsupportedEncodingException {
    // Get the length of the protocol identifier from the first byte.
    int pstrlen = Byte.valueOf(buffer.get()).intValue();
    // Check that the length is correct given the remaining data.
    if (pstrlen < 0 ||
            buffer.remaining() != BASE_HANDSHAKE_LENGTH + pstrlen - 1) {
      throw new ParseException("Incorrect handshake message length " +
              "(pstrlen=" + pstrlen + ") !", 0);
    }

    // Parse the protocol identifier and validate it.
    byte[] pstr = new byte[pstrlen];
    buffer.get(pstr);

    if (!Handshake.BITTORRENT_PROTOCOL_IDENTIFIER.equals(
            new String(pstr, Constants.BYTE_ENCODING))) {
      throw new ParseException("Invalid protocol identifier!", 1);
    }

    // Skip over the reserved bytes, which are not currently used.
    byte[] reserved = new byte[8];
    buffer.get(reserved);

    // Parse the info hash and peer ID from the buffer.
    byte[] infoHash = new byte[20];
    buffer.get(infoHash);
    byte[] peerId = new byte[20];
    buffer.get(peerId);
    // Return a new handshake object with the parsed data.
    return new Handshake(buffer, ByteBuffer.wrap(infoHash),
            ByteBuffer.wrap(peerId));
  }

  // Additional overloaded parse method which also sets the torrent identifier.
  public static Handshake parse(ByteBuffer buffer, String torrentIdentifier) throws UnsupportedEncodingException, ParseException {
    Handshake hs = Handshake.parse(buffer);
    hs.setTorrentIdentifier(torrentIdentifier);
    return hs;
  }

  // Additional overloaded parse method which also sets the protocol identifier length.
  public static Handshake parse(ByteBuffer buffer, int pstrlen) throws UnsupportedEncodingException, ParseException {
    Handshake hs = Handshake.parse(buffer);
    hs.myPstrlen = pstrlen;
    return hs;
  }

  // Method to craft a new handshake message given a torrent info hash and peer ID.
  public static Handshake craft(byte[] torrentInfoHash, byte[] clientPeerId) {
    try {
      // Allocate a ByteBuffer with the size of the handshake message.
      ByteBuffer buffer = ByteBuffer.allocate(
              Handshake.BASE_HANDSHAKE_LENGTH +
                      Handshake.BITTORRENT_PROTOCOL_IDENTIFIER.length());

      byte[] reserved = new byte[8]; // Reserved bytes, not used.
      ByteBuffer infoHash = ByteBuffer.wrap(torrentInfoHash);
      ByteBuffer peerId = ByteBuffer.wrap(clientPeerId);

      // Construct the handshake message in the buffer.
      buffer.put((byte) Handshake
              .BITTORRENT_PROTOCOL_IDENTIFIER.length());
      buffer.put(Handshake
              .BITTORRENT_PROTOCOL_IDENTIFIER.getBytes(Constants.BYTE_ENCODING));
      buffer.put(reserved);
      buffer.put(infoHash);
      buffer.put(peerId);

      // Return a new handshake object with the constructed message.
      return new Handshake(buffer, infoHash, peerId);
    } catch (UnsupportedEncodingException uee) {
      return null; // In case the encoding is not supported, return null.
    }
  }

  // Additional method to craft a handshake message with the torrent identifier set.
  public static Handshake parse(byte[] torrentInfoHash, byte[] clientPeerId, String torrentIdentifier) throws UnsupportedEncodingException, ParseException {
    Handshake hs = Handshake.craft(torrentInfoHash, clientPeerId);
    hs.setTorrentIdentifier(torrentIdentifier);
    return hs;
  }

  // Sets the torrent identifier for this handshake.
  public void setTorrentIdentifier(String torrentIdentifier) {
    this.torrentIdentifier = torrentIdentifier;
  }

  // Gets the protocol identifier length for this handshake.
  public int getPstrlen() {
    return myPstrlen;
  }

  // Gets the torrent identifier.
  public String getTorrentIdentifier() {
    return torrentIdentifier;
  }
}
