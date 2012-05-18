/**
 * Copyright (C) 2012 Turn, Inc.
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
package com.turn.ttorrent.common.protocol.udp;

import com.turn.ttorrent.common.Torrent;
import com.turn.ttorrent.common.protocol.TrackerMessage;

import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;


/**
 * The error message for the UDP tracker protocol.
 *
 * @author mpetazzoni
 */
public class UDPTrackerErrorMessage
	extends UDPTrackerMessage.UDPTrackerResponseMessage
	implements TrackerMessage.ErrorMessage {

	private static final int UDP_TRACKER_ERROR_MIN_MESSAGE_SIZE = 8;

	private final int actionId = Type.ERROR.getId();
	private final int transactionId;
	private final String reason;

	private UDPTrackerErrorMessage(ByteBuffer data, int transactionId,
		String reason) {
		super(Type.ERROR, data);
		this.transactionId = transactionId;
		this.reason = reason;
	}

	@Override
	public int getActionId() {
		return this.actionId;
	}

	@Override
	public int getTransactionId() {
		return this.transactionId;
	}

	@Override
	public String getReason() {
		return this.reason;
	}

	public static UDPTrackerErrorMessage parse(ByteBuffer data)
		throws MessageValidationException {
		if (data.remaining() < UDP_TRACKER_ERROR_MIN_MESSAGE_SIZE) {
			throw new MessageValidationException(
				"Invalid tracker error message size!");
		}

		if (data.getInt() != Type.ERROR.getId()) {
			throw new MessageValidationException(
				"Invalid action code for tracker error!");
		}

		int transactionId = data.getInt();
		byte[] reasonBytes = new byte[data.remaining()];
		data.get(reasonBytes);

		try {
			return new UDPTrackerErrorMessage(data,
				transactionId,
				new String(reasonBytes, Torrent.BYTE_ENCODING)
			);
		} catch (UnsupportedEncodingException uee) {
			throw new MessageValidationException(
				"Could not decode error message!", uee);
		}
	}

	public static UDPTrackerErrorMessage craft(int transactionId,
		String reason) throws UnsupportedEncodingException {
		byte[] reasonBytes = reason.getBytes(Torrent.BYTE_ENCODING);
		ByteBuffer data = ByteBuffer
			.allocate(UDP_TRACKER_ERROR_MIN_MESSAGE_SIZE +
				reasonBytes.length);
		data.putInt(Type.ERROR.getId());
		data.putInt(transactionId);
		data.put(reasonBytes);
		return new UDPTrackerErrorMessage(data,
			transactionId,
			reason);
	}
}
