/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.client;

import com.turn.ttorrent.client.peer.PieceHandler;
import com.turn.ttorrent.client.peer.PeerHandler;
import com.turn.ttorrent.client.peer.Instrumentation;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.BitSet;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;

/**
 *
 * @author shevek
 */
public interface PeerPieceProvider {

    @Nonnull
    public Instrumentation getInstrumentation();

    @Nonnull
    public String getLocalPeerName();

    @Nonnegative
    public int getPieceCount();

    @Nonnull
    public int getPieceLength(@Nonnegative int index);

    @Nonnegative
    public int getBlockLength();

    @Nonnull
    public BitSet getCompletedPieces();

    public boolean isCompletedPiece(@Nonnegative int index);

    /** Zero-copy. */
    public void andNotCompletedPieces(@Nonnull BitSet out);

    @CheckForNull
    public Iterable<PieceHandler.AnswerableRequestMessage> getNextPieceHandler(@Nonnull PeerHandler peer, @Nonnull BitSet interesting);

    public int addRequestTimeout(@Nonnull Iterable<? extends PieceHandler.AnswerableRequestMessage> requests);

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
     * @throws IllegalArgumentException If <em>offset + length</em> goes over
     * the piece boundary.
     * @throws IllegalStateException If the piece is not valid when attempting
     * to read it.
     * @throws IOException If the read can't be completed (I/O error, or EOF
     * reached, which can happen if the piece is not complete).
     */
    public void readBlock(@Nonnull ByteBuffer block, @Nonnegative int piece, @Nonnegative int offset) throws IOException;

    /**
     * Consumes the block.
     */
    public void writeBlock(@Nonnull ByteBuffer block, @Nonnegative int piece, @Nonnegative int offset) throws IOException;

    public boolean validateBlock(@Nonnegative ByteBuffer block, @Nonnegative int piece) throws IOException;
}
