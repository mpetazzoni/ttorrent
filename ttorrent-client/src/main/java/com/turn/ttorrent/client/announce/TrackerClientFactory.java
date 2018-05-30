/*
 * Copyright 2000-2018 JetBrains s.r.o.
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

package com.turn.ttorrent.client.announce;

import com.turn.ttorrent.common.Peer;

import java.net.URI;
import java.net.UnknownHostException;
import java.net.UnknownServiceException;
import java.util.List;

public interface TrackerClientFactory {

  /**
   * Create a {@link TrackerClient} announcing to the given tracker address.
   *
   * @param peers   The list peer the tracker client will announce on behalf of.
   * @param tracker The tracker address as a {@link java.net.URI}.
   * @throws UnknownHostException    If the tracker address is invalid.
   * @throws UnknownServiceException If the tracker protocol is not supported.
   */
  TrackerClient createTrackerClient(List<Peer> peers, URI tracker) throws UnknownHostException, UnknownServiceException;

}
