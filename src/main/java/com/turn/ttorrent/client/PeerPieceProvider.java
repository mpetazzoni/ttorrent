/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.client;

import com.turn.ttorrent.client.peer.DownloadingPiece;
import com.turn.ttorrent.client.peer.SharingPeer;
import java.io.IOException;
import java.nio.ByteBuffer;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;

/**
 *
 * @author shevek
 */
public interface PeerPieceProvider {

    @Nonnull
    public byte[] getInfoHash();

    @Nonnegative
    public int getPieceCount();

    @Nonnull
    public Piece getPiece(@Nonnegative int index);

    @CheckForNull
    public DownloadingPiece getNextPieceToDownload(@Nonnull SharingPeer peer);

    /**
     * Read a piece block from the underlying byte storage.
     *
     * <p>
     * This is the public method for reading this piece's data, and it will
     * only succeed if the piece is complete and valid on disk, thus ensuring
     * any data that comes out of this function is valid piece data we can send
     * to other peers.
     * </p>
     *
     * @param offset Offset inside this piece where to start reading.
     * @param length Number of bytes to read from the piece.
     * @return A byte buffer containing the piece data.
     * @throws IllegalArgumentException If <em>offset + length</em> goes over
     * the piece boundary.
     * @throws IllegalStateException If the piece is not valid when attempting
     * to read it.
     * @throws IOException If the read can't be completed (I/O error, or EOF
     * reached, which can happen if the piece is not complete).
     */
    public void readBlock(@Nonnull ByteBuffer block, @Nonnegative int piece, @Nonnegative int offset) throws IOException;

    public void writeBlock(@Nonnull ByteBuffer block, @Nonnegative int piece, @Nonnegative int offset) throws IOException;
}
