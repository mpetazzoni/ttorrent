/*
 * Copyright 2014 shevek.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.turn.ttorrent.tracker;

/**
 * Represents the state of a peer exchanging on this torrent.
 *
 * <p>
 * Peers can be in the STARTED state, meaning they have announced
 * themselves to us and are eventually exchanging data with other peers.
 * Note that a peer starting with a completed file will also be in the
 * started state and will never notify as being in the completed state.
 * This information can be inferred from the fact that the peer reports 0
 * bytes left to download.
 * </p>
 *
 * <p>
 * Peers enter the COMPLETED state when they announce they have entirely
 * downloaded the file. As stated above, we may also elect them for this
 * state if they report 0 bytes left to download.
 * </p>
 *
 * <p>
 * Peers enter the STOPPED state very briefly before being removed. We
 * still pass them to the STOPPED state in case someone else kept a
 * reference on them.
 * </p>
 */
public enum TrackedPeerState {

    UNKNOWN, STARTED, COMPLETED, STOPPED
}
