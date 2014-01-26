/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.test;

import com.turn.ttorrent.client.PeerPieceProvider;
import com.turn.ttorrent.client.Piece;
import com.turn.ttorrent.client.peer.PieceHandler;
import com.turn.ttorrent.client.peer.PeerHandler;
import com.turn.ttorrent.common.Torrent;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.BitSet;

/**
 *
 * @author shevek
 */
public class TestPeerPieceProvider implements PeerPieceProvider {

    private final Torrent torrent;
    private final Piece[] pieces;
    private final BitSet completedPieces;
    private PieceHandler pieceHandler;
    private final Object lock = new Object();

    public TestPeerPieceProvider(Torrent torrent) {
        this.torrent = torrent;
        this.pieces = new Piece[torrent.getPieceCount()];
        for (int i = 0; i < torrent.getPieceCount(); i++)
            pieces[i] = new Piece(torrent, i);
        this.completedPieces = new BitSet(pieces.length);
    }

    @Override
    public int getPieceCount() {
        return torrent.getPieceCount();
    }

    @Override
    public Piece getPiece(int index) {
        return pieces[index];
    }

    @Override
    public BitSet getCompletedPieces() {
        synchronized (lock) {
            return (BitSet) completedPieces.clone();
        }
    }

    @Override
    public void andNotCompletedPieces(BitSet out) {
        synchronized (lock) {
            out.andNot(completedPieces);
        }
    }

    @Override
    public PieceHandler getNextPieceToDownload(PeerHandler peer) {
        synchronized (lock) {
            PieceHandler out = pieceHandler;
            pieceHandler = null;
            return out;
        }
    }

    public void setPieceHandler(PieceHandler pieceHandler) {
        synchronized (lock) {
            this.pieceHandler = pieceHandler;
        }
    }

    public void setPieceHandler(int piece) {
        setPieceHandler(new PieceHandler(getPiece(piece), this));
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
}
