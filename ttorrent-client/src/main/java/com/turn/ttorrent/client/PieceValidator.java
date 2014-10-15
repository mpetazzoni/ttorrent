/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.client;

import com.turn.ttorrent.protocol.torrent.Torrent;
import java.nio.ByteBuffer;
import java.util.BitSet;
import java.util.concurrent.CountDownLatch;
import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link Runnable} to call the piece validation function.
 *
 * <p>
 * This {@link Runnable} implementation allows for the calling of the piece
 * validation function in a controlled context like a thread or an
 * executor.
 * </p>
 *
 * @author mpetazzoni
 */
/* pp */ class PieceValidator implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(PieceValidator.class);
    private final Torrent torrent;
    private final int piece;
    private final ByteBuffer data;
    private final BitSet valid;
    private final CountDownLatch latch;

    public PieceValidator(@Nonnull Torrent torrent, @Nonnegative int piece, @Nonnull ByteBuffer data, @Nonnull BitSet valid, @Nonnull CountDownLatch latch) {
        this.torrent = torrent;
        this.piece = piece;
        this.data = data;
        this.valid = valid;
        this.latch = latch;
    }

    @Override
    public void run() {
        try {
            if (torrent.isPieceValid(piece, data)) {
                // TODO: Synchronization on this lock may slow this down a lot.
                synchronized (valid) {
                    valid.set(piece);
                }
            }
        } catch (Exception e) {
            LOG.error("Failed validation of " + this, e);
        } finally {
            latch.countDown();
        }
    }
}
