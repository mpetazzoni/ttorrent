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
package com.turn.ttorrent.bcodec;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.EOFException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.io.input.AutoCloseInputStream;


/**
 * B-encoding decoder.
 *
 * <p>
 * A b-encoded byte stream can represent byte arrays, numbers, lists and maps
 * (dictionaries). This class implements a decoder of such streams into
 * {@link BEValue}s.
 * </p>
 *
 * <p>
 * Inspired by Snark's implementation.
 * </p>
 *
 * @author mpetazzoni
 * @see <a href="http://en.wikipedia.org/wiki/Bencode">B-encoding specification</a>
 */
public class BDecoder {

	// The InputStream to BDecode.
	private final InputStream in;

	// The last indicator read.
	// Zero if unknown.
	// '0'..'9' indicates a byte[].
	// 'i' indicates an Number.
	// 'l' indicates a List.
	// 'd' indicates a Map.
	// 'e' indicates end of Number, List or Map (only used internally).
	// -1 indicates end of stream.
	// Call getNextIndicator to get the current value (will never return zero).
	private int indicator = 0;

	/**
	 * Initializes a new BDecoder.
	 *
	 * <p>
	 * Nothing is read from the given <code>InputStream</code> yet.
	 * </p>
	 *
	 * @param in The input stream to read from.
	 */
	public BDecoder(InputStream in) {
		this.in = in;
	}

	/**
	 * Decode a B-encoded stream.
	 *
	 * <p>
	 * Automatically instantiates a new BDecoder for the provided input stream
	 * and decodes its root member.
	 * </p>
	 *
	 * @param in The input stream to read from.
	 */
	public static BEValue bdecode(InputStream in) throws IOException {
		return new BDecoder(in).bdecode();
	}

	/**
	 * Decode a B-encoded byte buffer.
	 *
	 * <p>
	 * Automatically instantiates a new BDecoder for the provided buffer and
	 * decodes its root member.
	 * </p>
	 *
	 * @param data The {@link ByteBuffer} to read from.
	 */
	public static BEValue bdecode(ByteBuffer data) throws IOException {
		return BDecoder.bdecode(new AutoCloseInputStream(
			new ByteArrayInputStream(data.array())));
	}

	/**
	 * Returns what the next b-encoded object will be on the stream or -1
	 * when the end of stream has been reached.
	 *
	 * <p>
	 * Can return something unexpected (not '0' .. '9', 'i', 'l' or 'd') when
	 * the stream isn't b-encoded.
	 * </p>
	 *
	 * This might or might not read one extra byte from the stream.
	 */
	private int getNextIndicator() throws IOException {
		if (this.indicator == 0) {
			this.indicator = in.read();
		}
		return this.indicator;
	}

	/**
	 * Gets the next indicator and returns either null when the stream
	 * has ended or b-decodes the rest of the stream and returns the
	 * appropriate BEValue encoded object.
	 */
	public BEValue bdecode() throws IOException	{
		if (this.getNextIndicator() == -1)
			return null;

		if (this.indicator >= '0' && this.indicator <= '9')
			return this.bdecodeBytes();
		else if (this.indicator == 'i')
			return this.bdecodeNumber();
		else if (this.indicator == 'l')
			return this.bdecodeList();
		else if (this.indicator == 'd')
			return this.bdecodeMap();
		else
			throw new InvalidBEncodingException
				("Unknown indicator '" + this.indicator + "'");
	}

	/**
	 * Returns the next b-encoded value on the stream and makes sure it is a
	 * byte array.
	 *
	 * @throws InvalidBEncodingException If it is not a b-encoded byte array.
	 */
	public BEValue bdecodeBytes() throws IOException {
		int c = this.getNextIndicator();
		int num = c - '0';
		if (num < 0 || num > 9)
			throw new InvalidBEncodingException("Number expected, not '"
					+ (char)c + "'");
		this.indicator = 0;

		c = this.read();
		int i = c - '0';
		while (i >= 0 && i <= 9) {
			// This can overflow!
			num = num*10 + i;
			c = this.read();
			i = c - '0';
		}

		if (c != ':') {
			throw new InvalidBEncodingException("Colon expected, not '" +
				(char)c + "'");
		}

		return new BEValue(read(num));
	}

	/**
	 * Returns the next b-encoded value on the stream and makes sure it is a
	 * number.
	 *
	 * @throws InvalidBEncodingException If it is not a number.
	 */
	public BEValue bdecodeNumber() throws IOException {
		int c = this.getNextIndicator();
		if (c != 'i') {
			throw new InvalidBEncodingException("Expected 'i', not '" +
				(char)c + "'");
		}
		this.indicator = 0;

		c = this.read();
		if (c == '0') {
			c = this.read();
			if (c == 'e')
				return new BEValue(BigInteger.ZERO);
			else
				throw new InvalidBEncodingException("'e' expected after zero," +
					" not '" + (char)c + "'");
		}

		// We don't support more the 255 char big integers
		char[] chars = new char[256];
		int off = 0;

		if (c == '-') {
			c = this.read();
			if (c == '0')
				throw new InvalidBEncodingException("Negative zero not allowed");
			chars[off] = (char)c;
			off++;
		}

		if (c < '1' || c > '9')
			throw new InvalidBEncodingException("Invalid Integer start '"
					+ (char)c + "'");
		chars[off] = (char)c;
		off++;

		c = this.read();
		int i = c - '0';
		while (i >= 0 && i <= 9) {
			chars[off] = (char)c;
			off++;
			c = read();
			i = c - '0';
		}

		if (c != 'e')
			throw new InvalidBEncodingException("Integer should end with 'e'");

		String s = new String(chars, 0, off);
		return new BEValue(new BigInteger(s));
	}

	/**
	 * Returns the next b-encoded value on the stream and makes sure it is a
	 * list.
	 *
	 * @throws InvalidBEncodingException If it is not a list.
	 */
	public BEValue bdecodeList() throws IOException {
		int c = this.getNextIndicator();
		if (c != 'l') {
			throw new InvalidBEncodingException("Expected 'l', not '" +
				(char)c + "'");
		}
		this.indicator = 0;

		List<BEValue> result = new ArrayList<BEValue>();
		c = this.getNextIndicator();
		while (c != 'e') {
			result.add(this.bdecode());
			c = this.getNextIndicator();
		}
		this.indicator = 0;

		return new BEValue(result);
	}

	/**
	 * Returns the next b-encoded value on the stream and makes sure it is a
	 * map (dictionary).
	 *
	 * @throws InvalidBEncodingException If it is not a map.
	 */
	public BEValue bdecodeMap() throws IOException {
		int c = this.getNextIndicator();
		if (c != 'd') {
			throw new InvalidBEncodingException("Expected 'd', not '" +
				(char)c + "'");
		}
		this.indicator = 0;

		Map<String, BEValue> result = new HashMap<String, BEValue>();
		c = this.getNextIndicator();
		while (c != 'e') {
			// Dictionary keys are always strings.
			String key = this.bdecode().getString();

			BEValue value = this.bdecode();
			result.put(key, value);

			c = this.getNextIndicator();
		}
		this.indicator = 0;

		return new BEValue(result);
	}

	/**
	 * Returns the next byte read from the InputStream (as int).
	 *
	 * @throws EOFException If InputStream.read() returned -1.
	 */
	private int read() throws IOException {
		int c = this.in.read();
		if (c == -1)
			throw new EOFException();
		return c;
	}

	/**
	 * Returns a byte[] containing length valid bytes starting at offset zero.
	 *
	 * @throws EOFException If InputStream.read() returned -1 before all
	 * requested bytes could be read.  Note that the byte[] returned might be
	 * bigger then requested but will only contain length valid bytes.  The
	 * returned byte[] will be reused when this method is called again.
	 */
	private byte[] read(int length) throws IOException {
		byte[] result = new byte[length];

		int read = 0;
		while (read < length)
		{
			int i = this.in.read(result, read, length - read);
			if (i == -1)
				throw new EOFException();
			read += i;
		}

		return result;
	}
}
