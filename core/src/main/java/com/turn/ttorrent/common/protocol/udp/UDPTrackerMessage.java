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
 * Base class for UDP tracker messages.
 *
 * @author mpetazzoni
 */
public abstract class UDPTrackerMessage extends TrackerMessage {

	private UDPTrackerMessage(Type type, ByteBuffer data) {
		super(type, data);
	}

	public abstract int getActionId();
	public abstract int getTransactionId();

	public static abstract class UDPTrackerRequestMessage
		extends UDPTrackerMessage {

		private static final int UDP_MIN_REQUEST_PACKET_SIZE = 16;

		protected UDPTrackerRequestMessage(Type type, ByteBuffer data) {
			super(type, data);
		}

		public static UDPTrackerRequestMessage parse(ByteBuffer data)
			throws MessageValidationException {
			if (data.remaining() < UDP_MIN_REQUEST_PACKET_SIZE) {
				throw new MessageValidationException("Invalid packet size!");
			}

			/**
			 * UDP request packets always start with the connection ID (8 bytes),
			 * followed by the action (4 bytes). Extract the action code
			 * accordingly.
			 */
			data.mark();
			data.getLong();
			int action = data.getInt();
			data.reset();

			if (action == Type.CONNECT_REQUEST.getId()) {
				return UDPConnectRequestMessage.parse(data);
			} else if (action == Type.ANNOUNCE_REQUEST.getId()) {
				return UDPAnnounceRequestMessage.parse(data);
			}

			throw new MessageValidationException("Unknown UDP tracker " +
				"request message!");
		}
	};

	public static abstract class UDPTrackerResponseMessage
		extends UDPTrackerMessage {

		private static final int UDP_MIN_RESPONSE_PACKET_SIZE = 8;

		protected UDPTrackerResponseMessage(Type type, ByteBuffer data) {
			super(type, data);
		}

		public static UDPTrackerResponseMessage parse(ByteBuffer data)
			throws MessageValidationException {
			if (data.remaining() < UDP_MIN_RESPONSE_PACKET_SIZE) {
				throw new MessageValidationException("Invalid packet size!");
			}

			/**
			 * UDP response packets always start with the action (4 bytes), so
			 * we can extract it immediately.
			 */
			data.mark();
			int action = data.getInt();
			data.reset();

			if (action == Type.CONNECT_RESPONSE.getId()) {
				return UDPConnectResponseMessage.parse(data);
			} else if (action == Type.ANNOUNCE_RESPONSE.getId()) {
				return UDPAnnounceResponseMessage.parse(data);
			} else if (action == Type.ERROR.getId()) {
				return UDPTrackerErrorMessage.parse(data);
			}

			throw new MessageValidationException("Unknown UDP tracker " +
				"response message!");
		}
	};
}
