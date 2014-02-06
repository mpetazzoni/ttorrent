/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.test;

import com.turn.ttorrent.client.PeerPieceProvider;
import com.turn.ttorrent.client.peer.PieceHandler;
import com.turn.ttorrent.client.peer.PeerHandler;
import com.turn.ttorrent.client.peer.Instrumentation;
import com.turn.ttorrent.common.Torrent;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.BitSet;

/**
 *
 * @author shevek
 */
public class TestPeerPieceProvider implements PeerPieceProvider {

    private final Instrumentation instrumentation = new Instrumentation();
    private final Torrent torrent;
    private final BitSet completedPieces;
    private PieceHandler pieceHandler;
    private final Object lock = new Object();

    public TestPeerPieceProvider(Torrent torrent) {
        this.torrent = torrent;
        this.completedPieces = new BitSet(torrent.getPieceCount());
    }

    @Override
    public Instrumentation getInstrumentation() {
        return instrumentation;
    }

    @Override
    public String getLocalPeerName() {
        return "<TestPeerPieceProvider>";
    }

    @Override
    public int getPieceCount() {
        return torrent.getPieceCount();
    }

    @Override
    public int getPieceLength(int index) {
        return torrent.getPieceLength(index);
    }

    @Override
    public int getBlockLength() {
        return PieceHandler.DEFAULT_BLOCK_SIZE;
    }

    @Override
    public BitSet getCompletedPieces() {
        synchronized (lock) {
            return (BitSet) completedPieces.clone();
        }
    }

    @Override
    public boolean isCompletedPiece(int index) {
        synchronized (lock) {
            return completedPieces.get(index);
        }
    }

    @Override
    public void andNotCompletedPieces(BitSet out) {
        synchronized (lock) {
            out.andNot(completedPieces);
        }
    }

    @Override
    public Iterable<PieceHandler.AnswerableRequestMessage> getNextPieceHandler(PeerHandler peer, BitSet interesting) {
        synchronized (lock) {
            PieceHandler out = pieceHandler;
            pieceHandler = null;
            return out;
        }
    }

    @Override
    public void addRequestTimeout(Iterable<? extends PieceHandler.AnswerableRequestMessage> requests) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    public void setPieceHandler(PieceHandler pieceHandler) {
        synchronized (lock) {
            this.pieceHandler = pieceHandler;
        }
    }

    public void setPieceHandler(int piece) {
        setPieceHandler(new PieceHandler(this, piece));
    }

    @Override
    public void readBlock(ByteBuffer block, int piece, int offset) throws IOException {
        // Apparently fill the block.
        block.position(block.limit());
    }

    @Override
    public void writeBlock(ByteBuffer block, int piece, int offset) throws IOException {
        // Apparently consume the block.
        block.position(block.limit());
    }

    @Override
    public boolean validateBlock(ByteBuffer block, int piece) throws IOException {
        return true;
    }
}
