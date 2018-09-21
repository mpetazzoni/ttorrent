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

package com.turn.ttorrent.client.strategy;

import com.turn.ttorrent.client.Piece;
import com.turn.ttorrent.client.peer.SharingPeer;

import java.util.List;
import java.util.Map;

public class RequestsCollectionImpl implements RequestsCollection {

  private final Map<Piece, List<SharingPeer>> selectedPieces;

  public RequestsCollectionImpl(Map<Piece, List<SharingPeer>> selectedPieces) {
    this.selectedPieces = selectedPieces;
  }

  @Override
  public void sendAllRequests() {
    for (Map.Entry<Piece, List<SharingPeer>> entry : selectedPieces.entrySet()) {
      Piece piece = entry.getKey();
      for (SharingPeer sharingPeer : entry.getValue()) {
        sharingPeer.downloadPiece(piece);
      }
    }
  }
}
