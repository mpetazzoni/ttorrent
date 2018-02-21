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
 * The connection request message for the UDP tracker protocol.
 *
 * @author mpetazzoni
 */
public class UDPConnectRequestMessage
	extends UDPTrackerMessage.UDPTrackerRequestMessage
	implements TrackerMessage.ConnectionRequestMessage {

	private static final int UDP_CONNECT_REQUEST_MESSAGE_SIZE = 16;
	private static final long UDP_CONNECT_REQUEST_MAGIC = 0x41727101980L;

	private final long connectionId = UDP_CONNECT_REQUEST_MAGIC;
	private final int actionId = Type.CONNECT_REQUEST.getId();
	private final int transactionId;

	private UDPConnectRequestMessage(ByteBuffer data, int transactionId) {
		super(Type.CONNECT_REQUEST, data);
		this.transactionId = transactionId;
	}

	public long getConnectionId() {
		return this.connectionId;
	}

	@Override
	public int getActionId() {
		return this.actionId;
	}

	@Override
	public int getTransactionId() {
		return this.transactionId;
	}

	public static UDPConnectRequestMessage parse(ByteBuffer data)
		throws MessageValidationException {
		if (data.remaining() != UDP_CONNECT_REQUEST_MESSAGE_SIZE) {
			throw new MessageValidationException(
				"Invalid connect request message size!");
		}

		if (data.getLong() != UDP_CONNECT_REQUEST_MAGIC) {
			throw new MessageValidationException(
				"Invalid connection ID in connection request!");
		}

		if (data.getInt() != Type.CONNECT_REQUEST.getId()) {
			throw new MessageValidationException(
				"Invalid action code for connection request!");
		}

		return new UDPConnectRequestMessage(data,
			data.getInt() // transactionId
		);
	}

	public static UDPConnectRequestMessage craft(int transactionId) {
		ByteBuffer data = ByteBuffer
			.allocate(UDP_CONNECT_REQUEST_MESSAGE_SIZE);
		data.putLong(UDP_CONNECT_REQUEST_MAGIC);
		data.putInt(Type.CONNECT_REQUEST.getId());
		data.putInt(transactionId);
		return new UDPConnectRequestMessage(data,
			transactionId);
	}
}
