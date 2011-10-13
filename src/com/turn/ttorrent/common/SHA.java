/*
 * Copyright (c) 1996, 2006, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package com.turn.ttorrent.common;

import static com.turn.ttorrent.common.ByteArrayAccess.*;

import java.security.DigestException;

/**
 * This class implements the Secure Hash Algorithm (SHA) developed by
 * the National Institute of Standards and Technology along with the
 * National Security Agency.  This is the updated version of SHA
 * fip-180 as superseded by fip-180-1.
 *
 * <p>It implement JavaSecurity MessageDigest, and can be used by in
 * the Java Security framework, as a pluggable implementation, as a
 * filter for the digest stream classes.
 *
 * @author	  Roger Riggs
 * @author	  Benjamin Renaud
 * @author	  Andreas Sterbenz
 */
public final class SHA {

	private final String algorithm = "SHA-1";

	// The state of this digest
	private static final int INITIAL = 0;
	private static final int IN_PROGRESS = 1;
	private int engineState = INITIAL;

	// one element byte array, temporary storage for update(byte)
	private byte[] oneByte;
	private byte[] buffer;
	// offset into buffer
	private int bufOfs;
	// size of the input to the compression function in bytes
	private final static int blockSize = 64;
	// length of the message digest in bytes
	private final static int digestLength = 20;

	// number of bytes processed so far. subclasses should not modify
	// this value.
	// also used as a flag to indicate reset status
	// -1: need to call engineReset() before next call to update()
	//  0: is already reset
	private long bytesProcessed;

	// Buffer of int's and count of characters accumulated
	// 64 bytes are included in each hash block so the low order
	// bits of count are used to know how to pack the bytes into ints
	// and to know when to compute the block and start the next one.
	private final int[] W;

	// state of this
	private final int[] state;

	/**
	 * Creates a new SHA object.
	 */
	public SHA() {
		state = new int[5];
		W = new int[80];
		buffer = new byte[blockSize];
		implReset();
	}

	/**
	 * Resets the buffers and hash value to start a new hash.
	 */
	void implReset() {
		state[0] = 0x67452301;
		state[1] = 0xefcdab89;
		state[2] = 0x98badcfe;
		state[3] = 0x10325476;
		state[4] = 0xc3d2e1f0;
	}

	/**
	 * Computes the final hash and copies the 20 bytes to the output array.
	 */
	void implDigest(byte[] out, int ofs) {
		long bitsProcessed = bytesProcessed << 3;

		int index = (int)bytesProcessed & 0x3f;
		int padLen = (index < 56) ? (56 - index) : (120 - index);
		engineUpdate(padding, 0, padLen);

		i2bBig4((int)(bitsProcessed >>> 32), buffer, 56);
		i2bBig4((int)bitsProcessed, buffer, 60);
		implCompress(buffer, 0);

		i2bBig(state, 0, out, ofs, 20);
	}

	// Constants for each round
	private final static int round1_kt = 0x5a827999;
	private final static int round2_kt = 0x6ed9eba1;
	private final static int round3_kt = 0x8f1bbcdc;
	private final static int round4_kt = 0xca62c1d6;

	/**
	 * Compute a the hash for the current block.
	 *
	 * This is in the same vein as Peter Gutmann's algorithm listed in
	 * the back of Applied Cryptography, Compact implementation of
	 * "old" NIST Secure Hash Algorithm.
	 */
	void implCompress(byte[] buf, int ofs) {
		b2iBig64(buf, ofs, W);

		// The first 16 ints have the byte stream, compute the rest of
		// the buffer
		for (int t = 16; t <= 79; t++) {
			int temp = W[t-3] ^ W[t-8] ^ W[t-14] ^ W[t-16];
			W[t] = (temp << 1) | (temp >>> 31);
		}

		int a = state[0];
		int b = state[1];
		int c = state[2];
		int d = state[3];
		int e = state[4];

		// Round 1
		for (int i = 0; i < 20; i++) {
			int temp = ((a<<5) | (a>>>(32-5))) +
				((b&c)|((~b)&d))+ e + W[i] + round1_kt;
			e = d;
			d = c;
			c = ((b<<30) | (b>>>(32-30)));
			b = a;
			a = temp;
		}

		// Round 2
		for (int i = 20; i < 40; i++) {
			int temp = ((a<<5) | (a>>>(32-5))) +
				(b ^ c ^ d) + e + W[i] + round2_kt;
			e = d;
			d = c;
			c = ((b<<30) | (b>>>(32-30)));
			b = a;
			a = temp;
		}

		// Round 3
		for (int i = 40; i < 60; i++) {
			int temp = ((a<<5) | (a>>>(32-5))) +
				((b&c)|(b&d)|(c&d)) + e + W[i] + round3_kt;
			e = d;
			d = c;
			c = ((b<<30) | (b>>>(32-30)));
			b = a;
			a = temp;
		}

		// Round 4
		for (int i = 60; i < 80; i++) {
			int temp = ((a<<5) | (a>>>(32-5))) +
				(b ^ c ^ d) + e + W[i] + round4_kt;
			e = d;
			d = c;
			c = ((b<<30) | (b>>>(32-30)));
			b = a;
			a = temp;
		}
		state[0] += a;
		state[1] += b;
		state[2] += c;
		state[3] += d;
		state[4] += e;
	}


	// padding used for the MD5, and SHA-* message digests
	static final byte[] padding;

	static {
		// we need 128 byte padding for SHA-384/512
		// and an additional 8 bytes for the high 8 bytes of the 16
		// byte bit counter in SHA-384/512
		padding = new byte[136];
		padding[0] = (byte)0x80;
	}

	// reset this object. See JCA doc.
	protected final void engineReset() {
		if (bytesProcessed == 0) {
			// already reset, ignore
			return;
		}
		implReset();
		bufOfs = 0;
		bytesProcessed = 0;
	}

	// single byte update. See JCA doc.
	protected final void engineUpdate(byte b) {
		if (oneByte == null) {
			oneByte = new byte[1];
		}
		oneByte[0] = b;
		engineUpdate(oneByte, 0, 1);
	}

	// array update. See JCA doc.
	protected final void engineUpdate(byte[] b, int ofs, int len) {
		if (len == 0) {
			return;
		}
		if ((ofs < 0) || (len < 0) || (ofs > b.length - len)) {
			throw new ArrayIndexOutOfBoundsException();
		}
		if (bytesProcessed < 0) {
			engineReset();
		}
		bytesProcessed += len;
		// if buffer is not empty, we need to fill it before proceeding
		if (bufOfs != 0) {
			int n = Math.min(len, blockSize - bufOfs);
			System.arraycopy(b, ofs, buffer, bufOfs, n);
			bufOfs += n;
			ofs += n;
			len -= n;
			if (bufOfs >= blockSize) {
				// compress completed block now
				implCompress(buffer, 0);
				bufOfs = 0;
			}
		}
		// compress complete blocks
		while (len >= blockSize) {
			implCompress(b, ofs);
			len -= blockSize;
			ofs += blockSize;
		}
		// copy remainder to buffer
		if (len > 0) {
			System.arraycopy(b, ofs, buffer, 0, len);
			bufOfs = len;
		}
	}

	// return the digest. See JCA doc.
	protected final byte[] engineDigest() throws DigestException {
		byte[] b = new byte[digestLength];
		engineDigest(b, 0, b.length);
		return b;
	}

	// return the digest in the specified array. See JCA doc.
	protected final int engineDigest(byte[] out, int ofs, int len)
			throws DigestException {
		if (len < digestLength) {
			throw new DigestException("Length must be at least "
				+ digestLength + " for " + algorithm + "digests");
		}
		if ((ofs < 0) || (len < 0) || (ofs > out.length - len)) {
			throw new DigestException("Buffer too short to store digest");
		}
		if (bytesProcessed < 0) {
			engineReset();
		}
		implDigest(out, ofs);
		bytesProcessed = -1;
		return digestLength;
	}

	/**
	 * Updates the digest using the specified array of bytes, starting
	 * at the specified offset.
	 *
	 * @param input the array of bytes.
	 *
	 * @param offset the offset to start from in the array of bytes.
	 *
	 * @param len the number of bytes to use, starting at
	 * <code>offset</code>.
	 */
	public void update(byte[] input, int offset, int len) {
		if (input == null) {
			throw new IllegalArgumentException("No input buffer given");
		}
		if (input.length - offset < len) {
			throw new IllegalArgumentException("Input buffer too short");
		}
		engineUpdate(input, offset, len);
		engineState = IN_PROGRESS;
	}

	/**
	 * Resets the digest for further use.
	 */
	public void reset() {
		engineReset();
		engineState = INITIAL;
	}

	/**
	 * Completes the hash computation by performing final operations
	 * such as padding. The digest is reset after this call is made.
	 *
	 * @return the array of bytes for the resulting hash value.
	 */
	public byte[] digest() throws DigestException {
		/* Resetting is the responsibility of implementors. */
		byte[] result = engineDigest();
		engineState = INITIAL;
		return result;
	}

}
