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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * B-encoding encoder.
 *
 * <p>
 * This class provides utility methods to encode objects and
 * {@link BEValue}s to B-encoding into a provided output stream.
 * </p>
 *
 * <p>
 * Inspired by Snark's implementation.
 * </p>
 *
 * @author mpetazzoni
 * @see <a href="http://en.wikipedia.org/wiki/Bencode">B-encoding specification</a>
 */
public class BEncoder {

	@SuppressWarnings("unchecked")
	public static void bencode(Object o, OutputStream out)
		throws IOException, IllegalArgumentException {
		if (o instanceof BEValue) {
			o = ((BEValue)o).getValue();
		}

		if (o instanceof String) {
			bencode((String)o, out);
		} else if (o instanceof byte[]) {
			bencode((byte[])o, out);
		} else if (o instanceof Number) {
			bencode((Number)o, out);
		} else if (o instanceof List) {
			bencode((List<BEValue>)o, out);
		} else if (o instanceof Map) {
			bencode((Map<String, BEValue>)o, out);
		} else {
			throw new IllegalArgumentException("Cannot bencode: " +
				o.getClass());
		}
	}

	public static void bencode(String s, OutputStream out) throws IOException {
		byte[] bs = s.getBytes("UTF-8");
		bencode(bs, out);
	}

	public static void bencode(Number n, OutputStream out) throws IOException {
		out.write('i');
		String s = n.toString();
		out.write(s.getBytes("UTF-8"));
		out.write('e');
	}

	public static void bencode(List<BEValue> l, OutputStream out)
		throws IOException {
		out.write('l');
		for (BEValue value : l) {
			bencode(value, out);
		}
		out.write('e');
	}

	public static void bencode(byte[] bs, OutputStream out) throws IOException {
		String l = Integer.toString(bs.length);
		out.write(l.getBytes("UTF-8"));
		out.write(':');
		out.write(bs);
	}

	public static void bencode(Map<String, BEValue> m, OutputStream out)
		throws IOException {
		out.write('d');

		// Keys must be sorted.
		Set<String> s = m.keySet();
		List<String> l = new ArrayList<String>(s);
		Collections.sort(l);

		for (String key : l) {
			Object value = m.get(key);
			bencode(key, out);
			bencode(value, out);
		}

		out.write('e');
	}

	public static ByteBuffer bencode(Map<String, BEValue> m)
		throws IOException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		BEncoder.bencode(m, baos);
		baos.close();
		return ByteBuffer.wrap(baos.toByteArray());
	}
}
