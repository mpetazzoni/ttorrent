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
package com.turn.ttorrent.client.peer;

import java.io.IOException;

import java.util.BitSet;
import java.util.EventListener;
import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;

/**
 * EventListener interface for objects that want to handle peer activity
 * events like piece availability, or piece completion events, and more.
 *
 * @author mpetazzoni
 */
public interface PeerActivityListener extends EventListener {

    /**
     * Peer choked handler.
     *
     * <p>
     * This handler is fired when a peer choked and now refuses to send data to
     * us. This means we should not try to request or expect anything from it
     * until it becomes ready again.
     * </p>
     *
     * @param peer The peer that choked.
     */
    public void handlePeerChoking(PeerHandler peer);

    /**
     * Peer ready handler.
     *
     * <p>
     * This handler is fired when a peer notified that it is no longer choked.
     * This means we can send piece block requests to it and start downloading.
     * </p>
     *
     * @param peer The peer that became ready.
     */
    public void handlePeerUnchoking(PeerHandler peer);

    /**
     * Piece availability handler.
     *
     * <p>
     * This handler is fired when an update in piece availability is received
     * from a peer's HAVE message.
     * </p>
     *
     * @param peer The peer we got the update from.
     * @param piece The piece that became available from this peer.
     */
    public void handlePieceAvailability(@Nonnull PeerHandler peer,
            @Nonnegative int piece);

    /**
     * Bit field availability handler.
     *
     * <p>
     * This handler is fired when an update in piece availability is received
     * from a peer's BITFIELD message.
     * </p>
     *
     * @param peer The peer we got the update from.
     * @param availablePieces The pieces availability bit field of the peer.
     */
    public void handleBitfieldAvailability(@Nonnull PeerHandler peer,
            @Nonnull BitSet prevAvailablePieces,
            @Nonnull BitSet availablePieces);

    /**
     * Piece upload completion handler.
     *
     * @param peer The peer the piece was sent to.
     * @param piece The piece in question.
     */
    public void handleBlockSent(@Nonnull PeerHandler peer,
            @Nonnegative int piece,
            @Nonnegative int offset, @Nonnegative int length);

    public void handleBlockReceived(@Nonnull PeerHandler peer,
            @Nonnegative int piece,
            @Nonnegative int offset, @Nonnegative int length);

    /**
     * Piece download completion handler.
     *
     * <p>
     * This handler is fired when a piece has been downloaded entirely and the
     * piece data has been revalidated.
     * </p>
     *
     * <p>
     * <b>Note:</b> the piece may <em>not</em> be valid after it has been
     * downloaded, in which case appropriate action should be taken to
     * redownload the piece.
     * </p>
     *
     * @param peer The peer we got this piece from.
     * @param piece The piece in question.
     */
    public void handlePieceCompleted(@Nonnull PeerHandler peer,
            @Nonnegative int piece,
            @Nonnull PieceHandler.Reception reception)
            throws IOException;
}
