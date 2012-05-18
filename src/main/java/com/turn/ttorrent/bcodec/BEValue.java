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

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


/**
 * A type-agnostic container for B-encoded values.
 *
 * @author mpetazzoni
 */
public class BEValue {

	/**
	 * The B-encoded value can be a byte array, a Number, a List or a Map.
	 * Lists and Maps contains BEValues too.
	 */
	private final Object value;

	public BEValue(byte[] value) {
		this.value = value;
	}

	public BEValue(String value) throws UnsupportedEncodingException {
		this.value = value.getBytes("UTF-8");
	}

	public BEValue(String value, String enc)
		throws UnsupportedEncodingException {
		this.value = value.getBytes(enc);
	}

	public BEValue(int value) {
		this.value = new Integer(value);
	}

	public BEValue(long value) {
		this.value = new Long(value);
	}

	public BEValue(Number value) {
		this.value = value;
	}

	public BEValue(List<BEValue> value) {
		this.value = value;
	}

	public BEValue(Map<String, BEValue> value) {
		this.value = value;
	}

	public Object getValue() {
		return this.value;
	}

	/**
	 * Returns this BEValue as a String, interpreted as UTF-8.
	 * @throws InvalidBEncodingException If the value is not a byte[].
	 */
	public String getString() throws InvalidBEncodingException {
		return this.getString("UTF-8");
	}

	/**
	 * Returns this BEValue as a String, interpreted with the specified
	 * encoding.
	 *
	 * @param encoding The encoding to interpret the bytes as when converting
	 * them into a {@link String}.
	 * @throws InvalidBEncodingException If the value is not a byte[].
	 */
	public String getString(String encoding) throws InvalidBEncodingException {
		try {
			return new String(this.getBytes(), encoding);
		} catch (ClassCastException cce) {
			throw new InvalidBEncodingException(cce.toString());
		} catch (UnsupportedEncodingException uee) {
			throw new InternalError(uee.toString());
		}
	}

	/**
	 * Returns this BEValue as a byte[].
	 *
	 * @throws InvalidBEncodingException If the value is not a byte[].
	 */
	public byte[] getBytes() throws InvalidBEncodingException {
		try {
			return (byte[])this.value;
		} catch (ClassCastException cce) {
			throw new InvalidBEncodingException(cce.toString());
		}
	}

	/**
	 * Returns this BEValue as a Number.
	 *
	 * @throws InvalidBEncodingException  If the value is not a {@link Number}.
	 */
	public Number getNumber() throws InvalidBEncodingException {
		try {
			return (Number)this.value;
		} catch (ClassCastException cce) {
			throw new InvalidBEncodingException(cce.toString());
		}
	}

	/**
	 * Returns this BEValue as short.
	 *
	 * @throws InvalidBEncodingException If the value is not a {@link Number}.
	 */
	public short getShort() throws InvalidBEncodingException {
		return this.getNumber().shortValue();
	}

	/**
	 * Returns this BEValue as int.
	 *
	 * @throws InvalidBEncodingException If the value is not a {@link Number}.
	 */
	public int getInt() throws InvalidBEncodingException {
		return this.getNumber().intValue();
	}

	/**
	 * Returns this BEValue as long.
	 *
	 * @throws InvalidBEncodingException If the value is not a {@link Number}.
	 */
	public long getLong() throws InvalidBEncodingException {
		return this.getNumber().longValue();
	}

	/**
	 * Returns this BEValue as a List of BEValues.
	 *
	 * @throws InvalidBEncodingException If the value is not an
	 * {@link ArrayList}.
	 */
	@SuppressWarnings("unchecked")
	public List<BEValue> getList() throws InvalidBEncodingException {
		if (this.value instanceof ArrayList) {
			return (ArrayList<BEValue>)this.value;
		} else {
			throw new InvalidBEncodingException("Excepted List<BEvalue> !");
		}
	}

	/**
	 * Returns this BEValue as a Map of String keys and BEValue values.
	 *
	 * @throws InvalidBEncodingException If the value is not a {@link HashMap}.
	 */
	@SuppressWarnings("unchecked")
	public Map<String, BEValue> getMap() throws InvalidBEncodingException {
		if (this.value instanceof HashMap) {
			return (Map<String, BEValue>)this.value;
		} else {
			throw new InvalidBEncodingException("Expected Map<String, BEValue> !");
		}
	}
}
