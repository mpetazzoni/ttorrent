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

import java.util.*;

public class EndGameStrategyImpl implements EndGameStrategy {

  private static final Random RANDOM = new Random();

  private final int peersPerPiece;

  public EndGameStrategyImpl(int peersPerPiece) {
    this.peersPerPiece = peersPerPiece;
  }

  @Override
  public RequestsCollection collectRequests(Piece[] allPieces, List<SharingPeer> connectedPeers) {
    List<SharingPeer> sorted = new ArrayList<SharingPeer>(connectedPeers);
    Map<Piece, List<SharingPeer>> selectedPieces = new HashMap<Piece, List<SharingPeer>>();
    Collections.sort(sorted, new Comparator<SharingPeer>() {
      @Override
      public int compare(SharingPeer o1, SharingPeer o2) {
        return Integer.valueOf(o1.getDownloadedPiecesCount()).compareTo(o2.getDownloadedPiecesCount());
      }
    });
    for (Piece piece : allPieces) {
      if (piece.isValid()) continue;

      //if we don't have piece, then request this piece from two random peers
      //(peers are selected by peer rank, peer with better rank will be selected more often then peer with bad rank
      List<SharingPeer> selectedPeers = selectGoodPeers(piece, peersPerPiece, sorted);
      selectedPieces.put(piece, selectedPeers);
    }
    return new RequestsCollectionImpl(selectedPieces);
  }

  private List<SharingPeer> selectGoodPeers(Piece piece, int count, List<SharingPeer> sortedPeers) {
    List<SharingPeer> notSelected = new ArrayList<SharingPeer>(sortedPeers);
    Iterator<SharingPeer> iterator = notSelected.iterator();
    while (iterator.hasNext()) {
      SharingPeer peer = iterator.next();
      boolean peerHasCurrentPiece = peer.getAvailablePieces().get(piece.getIndex());
      boolean alreadyRequested = peer.getRequestedPieces().contains(piece);

      if (!peerHasCurrentPiece || alreadyRequested) iterator.remove();
    }
    if (notSelected.size() <= count) return notSelected;

    List<SharingPeer> selected = new ArrayList<SharingPeer>();
    for (int i = 0; i < count; i++) {
      SharingPeer sharingPeer = selectPeer(notSelected);
      if (sharingPeer == null) continue;
      notSelected.remove(sharingPeer);
      selected.add(sharingPeer);
    }

    return selected;
  }

  private SharingPeer selectPeer(List<SharingPeer> notSelected) {
    for (SharingPeer sharingPeer : notSelected) {
      if (RANDOM.nextDouble() < 0.8) {
        return sharingPeer;
      }
    }
    return notSelected.get(RANDOM.nextInt(notSelected.size()));
  }
}
