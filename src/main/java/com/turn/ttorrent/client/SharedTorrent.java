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
package com.turn.ttorrent.client;

import com.google.common.base.Throwables;
import com.turn.ttorrent.common.Torrent;
import com.turn.ttorrent.client.peer.SharingPeer;
import com.turn.ttorrent.client.storage.TorrentByteStorage;
import com.turn.ttorrent.client.storage.FileStorage;
import com.turn.ttorrent.client.storage.FileCollectionStorage;

import com.turn.ttorrent.common.TorrentCreator;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.util.BitSet;
import java.util.LinkedList;
import java.util.List;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A torrent shared by the BitTorrent client.
 *
 * <p>
 * The {@link SharedTorrent} class embeds the Torrent class with all the data
 * and logic required by the BitTorrent client implementation.
 * </p>
 *
 * <p>
 * <em>Note:</em> this implementation currently only supports single-file
 * torrents.
 * </p>
 *
 * @author mpetazzoni
 */
public class SharedTorrent {

    private static final Logger logger =
            LoggerFactory.getLogger(SharedTorrent.class);

    public enum SharedTorrentState {

        WAITING,
        VALIDATING,
        SHARING,
        SEEDING,
        ERROR,
        DONE;
    };
    private final Client client;
    private final Torrent torrent;
    private final TorrentByteStorage bucket;
    private final TrackerHandler trackerHandler;
    private final PeerHandler peerHandler;
    @Nonnull
    private SharedTorrentState state = SharedTorrentState.WAITING;
    private Piece[] pieces;
    // private SortedSet<Piece> rarest;
    private BitSet completedPieces = new BitSet();
    private double maxUploadRate = 0.0;
    private double maxDownloadRate = 0.0;
    private final Object lock = new Object();

    /**
     * Create a new shared torrent from meta-info binary data.
     *
     * @param torrent The meta-info byte data.
     * @param destDir The destination directory or location of the torrent
     * files.
     * @throws FileNotFoundException If the torrent file location or
     * destination directory does not exist and can't be created.
     * @throws IOException If the torrent file cannot be read or decoded.
     */
    public SharedTorrent(@Nonnull Client client, @Nonnull byte[] torrent, @Nonnull File destDir)
            throws IOException, URISyntaxException {
        this(client, new Torrent(torrent), destDir);
    }

    /**
     * Create a new shared torrent from a base Torrent object.
     *
     * <p>
     * This will recreate a SharedTorrent object from the provided Torrent
     * object's encoded meta-info data.
     * </p>
     *
     * @param torrent The Torrent object.
     * @param destDir The destination directory or location of the torrent
     * files.
     * @throws FileNotFoundException If the torrent file location or
     * destination directory does not exist and can't be created.
     * @throws IOException If the torrent file cannot be read or decoded.
     */
    public SharedTorrent(@Nonnull Client client, @Nonnull Torrent torrent, @Nonnull File destDir)
            throws IOException {
        this(client, torrent, toStorage(torrent, destDir));
    }

    @Nonnull
    private static TorrentByteStorage toStorage(@Nonnull Torrent torrent, @CheckForNull File parent)
            throws IOException {
        if (parent == null || !parent.isDirectory()) {
            throw new IllegalArgumentException("Invalid parent directory!");
        }

        String parentPath = parent.getCanonicalPath();

        List<FileStorage> files = new LinkedList<FileStorage>();
        long offset = 0L;
        for (Torrent.TorrentFile file : torrent.getFiles()) {
            File actual = new File(parent, file.file.getPath());

            if (!actual.getCanonicalPath().startsWith(parentPath)) {
                throw new SecurityException("Torrent file path attempted "
                        + "to break directory jail!");
            }

            actual.getParentFile().mkdirs();
            files.add(new FileStorage(actual, offset, file.size));
            offset += file.size;
        }
        return new FileCollectionStorage(files, torrent.getSize());
    }

    /**
     * Create a new shared torrent from meta-info binary data.
     *
     * @param torrent The meta-info byte data.
     * @param parent The parent directory or location the torrent files.
     * @param seeder Whether we're a seeder for this torrent or not (disables
     * validation).
     * @throws FileNotFoundException If the torrent file location or
     * destination directory does not exist and can't be created.
     * @throws IOException If the torrent file cannot be read or decoded.
     */
    public SharedTorrent(@Nonnull Client client, @Nonnull Torrent torrent, @Nonnull TorrentByteStorage bucket)
            throws IOException {
        this.client = client;
        this.torrent = torrent;
        this.bucket = bucket;
        this.trackerHandler = new TrackerHandler(this);
        this.peerHandler = new PeerHandler(this);
    }

    @Nonnull
    public Client getClient() {
        return client;
    }

    @Nonnull
    public Torrent getTorrent() {
        return torrent;
    }

    @Nonnull
    public TorrentByteStorage getBucket() {
        return bucket;
    }

    @Nonnull
    public String getName() {
        return getTorrent().getName();
    }

    @Nonnull
    public byte[] getInfoHash() {
        return getTorrent().getInfoHash();
    }

    @Nonnegative
    public long getSize() {
        return getTorrent().getSize();
    }

    @Nonnegative
    public int getPieceCount() {
        return getTorrent().getPieceCount();
    }

    @Nonnegative
    public int getPieceLength() {
        return getTorrent().getPieceLength();
    }

    /**
     * @see Torrent#getPieceLength(int)
     */
    @Nonnegative
    public int getPieceLength(@Nonnegative int index) {
        return getTorrent().getPieceLength(index);
    }

    @Nonnegative
    public int getBlockSize() {
        return PieceBlock.DEFAULT_SIZE;
    }

    @Nonnull
    public TrackerHandler getTrackerHandler() {
        return trackerHandler;
    }

    @Nonnull
    public PeerHandler getPeerHandler() {
        return peerHandler;
    }

    @Nonnull
    public SharedTorrentState getState() {
        synchronized (lock) {
            return state;
        }
    }

    public void setState(@Nonnull SharedTorrentState state) {
        synchronized (lock) {
            this.state = state;
        }
    }

    /**
     * Retrieve a piece object by index.
     *
     * @param index The index of the piece in this torrent.
     */
    @Nonnull
    public Piece getPiece(@Nonnegative int index) {
        if (!isInitialized())
            throw new IllegalStateException("Torrent not initialized yet.");
        if (index >= getPieceCount())
            throw new IllegalArgumentException("Invalid piece index!");
        // Don't need to lock here again because isInitialized() raised it.
        return this.pieces[index];
    }

    /**
     * Return a copy of the completed pieces bitset.
     */
    @Nonnull
    public BitSet getCompletedPieces() {
        if (!this.isInitialized())
            throw new IllegalStateException("Torrent not yet initialized!");
        synchronized (lock) {
            return (BitSet) completedPieces.clone();
        }
    }

    /**
     * Mark a piece as completed, decrementing the piece size in bytes from our
     * left bytes to download counter.
     */
    public void setCompletedPiece(@Nonnegative int index) {
        // A completed piece means that's that much data left to download for
        // this torrent.
        synchronized (lock) {
            this.completedPieces.set(index);
        }
    }

    public boolean isCompletedPiece(@Nonnegative int index) {
        synchronized (lock) {
            return completedPieces.get(index);
        }
    }

    @Nonnegative
    public int getCompletedPieceCount() {
        synchronized (lock) {
            return completedPieces.cardinality();
        }
    }

    public void andNotCompletedPieces(BitSet b) {
        synchronized (lock) {
            b.andNot(completedPieces);
        }
    }

    /**
     * Return a copy of the bit field of available pieces for this torrent.
     *
     * <p>
     * Available pieces are pieces available in the swarm, and it does not
     * include our own pieces.
     * </p>
     */
    @Nonnull
    public BitSet getAvailablePieces() {
        if (!this.isInitialized())
            throw new IllegalStateException("Torrent not yet initialized!");

        BitSet availablePieces = new BitSet(getPieceCount());
        synchronized (lock) {
            for (Piece piece : pieces) {
                if (piece.isAvailable())
                    availablePieces.set(piece.getIndex());
            }
        }

        return availablePieces;
    }

    @Nonnegative
    public int getAvailablePieceCount() {
        synchronized (lock) {
            int count = 0;
            for (Piece piece : pieces)
                if (piece.isAvailable())
                    count++;
            return count;
        }
    }

    /**
     * Return the completion percentage of this torrent.
     *
     * <p>
     * This is computed from the number of completed pieces divided by the
     * number of pieces in this torrent, times 100.
     * </p>
     */
    public float getCompletion() {
        return this.isInitialized()
                ? (float) getCompletedPieceCount() / getPieceCount() * 100.0f
                : 0.0f;
    }

    public double getMaxUploadRate() {
        return this.maxUploadRate;
    }

    /**
     * Set the maximum upload rate (in kB/second) for this
     * torrent. A setting of <= 0.0 disables rate limiting.
     *
     * @param rate The maximum upload rate
     */
    public void setMaxUploadRate(double rate) {
        this.maxUploadRate = rate;
    }

    public double getMaxDownloadRate() {
        return this.maxDownloadRate;
    }

    /**
     * Set the maximum download rate (in kB/second) for this
     * torrent. A setting of <= 0.0 disables rate limiting.
     *
     * @param rate The maximum download rate
     */
    public void setMaxDownloadRate(double rate) {
        this.maxDownloadRate = rate;
    }

    @Nonnegative
    public long getUploaded() {
        return peerHandler.getUploaded();
    }

    @Nonnegative
    public long getDownloaded() {
        return peerHandler.getDownloaded();
    }

    /**
     * Get the number of bytes left to download for this torrent.
     */
    public long getLeft() {
        synchronized (lock) {
            long count = getPieceCount() - getCompletedPieceCount();
            long left = count * getPieceLength();

            int lastPieceIndex = getPieceCount() - 1;
            if (!isCompletedPiece(lastPieceIndex)) {
                left -= getPieceLength();
                left += getPieceLength(lastPieceIndex);
            }

            return left;
        }
    }

    /**
     * Tells whether this torrent has been fully initialized yet.
     */
    public boolean isInitialized() {
        switch (getState()) {
            case WAITING:
            case VALIDATING:
                return false;
            case SHARING:
            case SEEDING:
                return true;
            case ERROR:
            case DONE:
            default:
                return false;
        }
    }

    /**
     * Build this torrent's pieces array.
     *
     * <p>
     * Hash and verify any potentially present local data and create this
     * torrent's pieces array from their respective hash provided in the
     * torrent meta-info.
     * </p>
     *
     * <p>
     * This function should be called soon after the constructor to initialize
     * the pieces array.
     * </p>
     */
    public void init() throws InterruptedException, IOException {
        if (this.isInitialized())
            throw new IllegalStateException("Torrent was already initialized!");
        setState(SharedTorrentState.VALIDATING);

        try {
            int npieces = torrent.getPieceCount();

            long size = getSize();
            // Store in a local so we can update with minimal synchronization.
            Piece[] pieces = new Piece[npieces];
            BitSet completedPieces = new BitSet(npieces);
            long completedSize = 0;

            ThreadPoolExecutor executor = TorrentCreator.newExecutor();

            try {
                logger.info("Analyzing local data for {} ({} pieces)...",
                        new Object[]{getName(), npieces});

                int step = 10;
                CountDownLatch latch = new CountDownLatch(npieces);
                for (int index = 0; index < npieces; index++) {
                    Piece piece = new Piece(this, index);
                    pieces[index] = piece;
                    // TODO: Read the file sequentially and pass it to the validator.
                    // Otherwise we thrash the disk on validation.
                    ByteBuffer buffer = ByteBuffer.allocate(getPieceLength(index));
                    bucket.read(buffer, piece.getOffset());
                    buffer.flip();
                    executor.execute(new Piece.Validator(pieces[index], buffer, latch));

                    if (index / (float) npieces * 100f > step) {
                        logger.info("  ... {}% complete", step);
                        step += 10;
                    }
                }
                latch.await();

                for (Piece piece : pieces) {
                    if (piece.isValid()) {
                        completedPieces.set(piece.getIndex());
                        completedSize += getPieceLength(piece.getIndex());
                    }
                }
            } finally {
                // Request orderly executor shutdown and wait for hashing tasks to
                // complete.
                executor.shutdown();
                executor.awaitTermination(1, TimeUnit.SECONDS);
            }

            logger.debug("{}: we have {}/{} bytes ({}%) [{}/{} pieces].",
                    new Object[]{
                getName(),
                completedSize,
                size,
                String.format("%.1f", (100f * (completedSize / (float) size))),
                completedPieces.cardinality(),
                pieces.length
            });

            synchronized (lock) {
                this.pieces = pieces;
                this.completedPieces = completedPieces;
            }

            if (isComplete())
                setState(SharedTorrentState.SEEDING);
            else
                setState(SharedTorrentState.SHARING);
        } catch (Exception e) {
            setState(SharedTorrentState.ERROR);
            Throwables.propagateIfPossible(e, InterruptedException.class, IOException.class);
            throw Throwables.propagate(e);
        } finally {
        }
    }

    /**
     * Display information about the BitTorrent client state.
     *
     * <p>
     * This emits an information line in the log about this client's state. It
     * includes the number of choked peers, number of connected peers, number
     * of known peers, information about the torrent availability and
     * completion and current transmission rates.
     * </p>
     */
    public void info() {
        int connected = 0;
        double dl = 0;
        double ul = 0;
        Map<? extends String, ? extends SharingPeer> peers = getPeerHandler().getPeers();
        for (SharingPeer peer : peers.values()) {
            if (peer.isConnected())
                connected++;
            dl += peer.getDLRate().rate(TimeUnit.SECONDS);
            ul += peer.getULRate().rate(TimeUnit.SECONDS);
        }

        logger.info("{} {}/{} pieces ({}%) [{}/{}] with {}/{} peers at {}/{} kB/s.",
                new Object[]{
            this.getState().name(),
            getCompletedPieceCount(),
            getPieceCount(),
            String.format("%.2f", getCompletion()),
            getAvailablePieceCount(),
            -42, // getRequestedPieceCount(),
            connected,
            peers.size(),
            String.format("%.2f", dl / 1024.0),
            String.format("%.2f", ul / 1024.0),});
        /*
         for (SharingPeer peer : peers.values()) {
         Piece piece = peer.getRequestedPiece();
         logger.debug("  | {} {}",
         peer,
         piece != null
         ? "(downloading " + piece + ")"
         : "");
         }
         */
    }

    /**
     * Finalize the download of this torrent.
     *
     * <p>
     * This realizes the final, pre-seeding phase actions on this torrent,
     * which usually consists in putting the torrent data in their final form
     * and at their target location.
     * </p>
     *
     * @see TorrentByteStorage#finish
     */
    public synchronized void finish() throws IOException {
        if (!this.isInitialized()) {
            throw new IllegalStateException("Torrent not yet initialized!");
        }

        if (!this.isComplete()) {
            throw new IllegalStateException("Torrent download is not complete!");
        }

        this.bucket.finish();
        trackerHandler.complete();
    }

    public synchronized boolean isFinished() {
        return this.isComplete() && this.bucket.isFinished();
    }

    /**
     * Tells whether this torrent has been fully downloaded, or is fully
     * available locally.
     */
    public synchronized boolean isComplete() {
        return getCompletedPieceCount() == getPieceCount();
    }

    public void close() throws IOException {
        // Determine final state
        if (isFinished())
            setState(SharedTorrentState.DONE);
        else
            setState(SharedTorrentState.ERROR);

        this.bucket.close();
    }

    /*
     * Reset peers download and upload rates.
     *
     * <p>
     * This method is called every RATE_COMPUTATION_ITERATIONS to reset the
     * download and upload rates of all peers. This contributes to making the
     * download and upload rate computations rolling averages every
     * UNCHOKING_FREQUENCY * RATE_COMPUTATION_ITERATIONS seconds (usually 20
     * seconds).
     * </p>
     private void tick() {
     for (SharingPeer peer : this.peers.values()) {
     peer.getDLRate().tick();
     peer.getULRate().tick();
     }
     }
     */
}