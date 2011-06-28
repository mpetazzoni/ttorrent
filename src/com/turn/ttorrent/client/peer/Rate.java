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

package com.turn.ttorrent.client.peer;

import java.util.Comparator;

/** A data exchange rate representation.
 *
 * This is a utility class to keep track, and compare, of the data exchange
 * rate (either download or upload) with a peer.
 *
 * @author mpetazzoni
 */
public class Rate {
	private long bytes = 0;
	private long reset = 0;
	private long last = 0;

	/** Add a byte count to the current measurement.
	 *
	 * @param count The number of bytes exchanged since the last reset.
	 */
	public synchronized void add(long count) {
		this.bytes += count;
		if (this.reset == 0) {
			this.reset = System.currentTimeMillis();
		}
		this.last = System.currentTimeMillis();
	}

	/** Get the current rate.
	 *
	 * The exchange rate is the number of bytes exchanged since the last
	 * reset and the last input.
	 */
	public synchronized float get() {
		if (this.last - this.reset == 0) {
			return 0;
		}

		return this.bytes / ((this.last - this.reset) / 1000.0f);
	}

	/** Reset the measurement.
	 */
	public synchronized void reset() {
		this.bytes = 0;
		this.reset = System.currentTimeMillis();
		this.last = this.reset;
	}

	/** A rate comparator.
	 *
	 * This class provides a comparator to sort peers by an exchange rate,
	 * comparing two rates and returning an ascending ordering.
	 *
	 * <b>Note:</b> we need to make sure here that we don't return 0, which
	 * would provide an ordering that is inconsistent with
	 * <code>equals()</code>'s behavior, and result in unpredictable behavior
	 * for sorted collections using this comparator.
	 *
	 * @author mpetazzoni
	 */
	public static class RateComparator implements Comparator<Rate> {
		@Override
		public int compare(Rate a, Rate b) {
			if (a.get() > b.get()) {
				return 1;
			} else {
				return -1;
			}
		}
	}
}


