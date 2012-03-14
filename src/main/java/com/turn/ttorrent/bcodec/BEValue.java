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

package com.turn.ttorrent.bcodec;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A type-agnostic container for B-encoded values.
 *
 * @author mpetazzoni
 */
public class BEValue {

	/* The B-encoded value can be a byte array, a Number, a List or a Map.
	 *
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

	/** Returns this BEValue as a String.
	 *
	 * This operation only succeeds when the BEValue is a byte[], otherwise it
	 * will throw a InvalidBEncodingException. The byte[] will be interpreted
	 * as UTF-8 encoded characters.
	 */
	public String getString() throws InvalidBEncodingException {
		try {
			return new String(this.getBytes(), "UTF-8");
		} catch (ClassCastException cce) {
			throw new InvalidBEncodingException(cce.toString());
		} catch (UnsupportedEncodingException uee) {
			throw new InternalError(uee.toString());
		}
	}

	/** Returns this BEValue as a byte[].
	 *
	 * This operation only succeeds when the BEValue is actually a byte[],
	 * otherwise it will throw a InvalidBEncodingException.
	 */
	public byte[] getBytes() throws InvalidBEncodingException {
		try {
			return (byte[])this.value;
		} catch (ClassCastException cce) {
			throw new InvalidBEncodingException(cce.toString());
		}
	}

	/** Returns this BEValue as a Number.
	 *
	 * This operation only succeeds when the BEValue is actually a Number,
	 * otherwise it will throw a InvalidBEncodingException.
	 */
	public Number getNumber() throws InvalidBEncodingException {
		try {
			return (Number)this.value;
		} catch (ClassCastException cce) {
			throw new InvalidBEncodingException(cce.toString());
		}
	}

	/** Returns this BEValue as int.
	 *
	 * This operation only succeeds when the BEValue is actually a Number,
	 * otherwise it will throw a InvalidBEncodingException. The returned int is
	 * the result of <code>Number.intValue()</code>.
	 */
	public int getInt() throws InvalidBEncodingException {
		return this.getNumber().intValue();
	}

	/** Returns this BEValue as long.
	 *
	 * This operation only succeeds when the BEValue is actually a Number,
	 * otherwise it will throw a InvalidBEncodingException. The returned long
	 * is the result of <code>Number.longValue()</code>.
	 */
	public long getLong() throws InvalidBEncodingException {
		return this.getNumber().longValue();
	}

	/** Returns this BEValue as a List of BEValues.
	 *
	 * This operation only succeeds when the BEValue is actually a List,
	 * otherwise it will throw a InvalidBEncodingException.
	 */
	@SuppressWarnings("unchecked")
	public List<BEValue> getList() throws InvalidBEncodingException {
		if (this.value instanceof ArrayList) {
			return (ArrayList<BEValue>)this.value;
		} else {
			throw new InvalidBEncodingException("Excepted List<BEvalue> !");
		}
	}

	/** Returns this BEValue as a Map of String keys and BEValue values.
	 *
	 * This operation only succeeds when the BEValue is actually a Map,
	 * otherwise it will throw a InvalidBEncodingException.
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
