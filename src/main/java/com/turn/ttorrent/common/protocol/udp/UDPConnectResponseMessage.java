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

import com.turn.ttorrent.common.protocol.TrackerMessage;

import java.nio.ByteBuffer;


/**
 * The connection response message for the UDP tracker protocol.
 *
 * @author mpetazzoni
 */
public class UDPConnectResponseMessage
	extends UDPTrackerMessage.UDPTrackerResponseMessage
	implements TrackerMessage.ConnectionResponseMessage {

	private static final int UDP_CONNECT_RESPONSE_MESSAGE_SIZE = 16;

	private final int actionId = Type.CONNECT_RESPONSE.getId();
	private final int transactionId;
	private final long connectionId;

	private UDPConnectResponseMessage(ByteBuffer data, int transactionId,
		long connectionId) {
		super(Type.CONNECT_RESPONSE, data);
		this.transactionId = transactionId;
		this.connectionId = connectionId;
	}

	@Override
	public int getActionId() {
		return this.actionId;
	}

	@Override
	public int getTransactionId() {
		return this.transactionId;
	}

	public long getConnectionId() {
		return this.connectionId;
	}

	public static UDPConnectResponseMessage parse(ByteBuffer data)
		throws MessageValidationException {
		if (data.remaining() != UDP_CONNECT_RESPONSE_MESSAGE_SIZE) {
			throw new MessageValidationException(
				"Invalid connect response message size!");
		}

		if (data.getInt() != Type.CONNECT_RESPONSE.getId()) {
			throw new MessageValidationException(
				"Invalid action code for connection response!");
		}

		return new UDPConnectResponseMessage(data,
			data.getInt(), // transactionId
			data.getLong() // connectionId
		);
	}

	public static UDPConnectResponseMessage craft(int transactionId,
		long connectionId) {
		ByteBuffer data = ByteBuffer
			.allocate(UDP_CONNECT_RESPONSE_MESSAGE_SIZE);
		data.putInt(Type.CONNECT_RESPONSE.getId());
		data.putInt(transactionId);
		data.putLong(connectionId);
		return new UDPConnectResponseMessage(data,
			transactionId,
			connectionId);
	}
}
