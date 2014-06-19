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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.io.Files;
import com.turn.ttorrent.common.Torrent;
import com.turn.ttorrent.client.peer.PieceHandler;
import com.turn.ttorrent.client.storage.TorrentByteStorage;
import com.turn.ttorrent.client.storage.FileStorage;
import com.turn.ttorrent.client.storage.FileCollectionStorage;

import com.turn.ttorrent.common.TorrentUtils;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

import java.net.SocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.util.BitSet;
import java.util.LinkedList;
import java.util.List;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;
import org.apache.commons.io.FileUtils;
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
public class TorrentHandler implements TorrentMetadataProvider {

    private static final Logger LOG = LoggerFactory.getLogger(TorrentHandler.class);

    public enum State {

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
    private final SwarmHandler swarmHandler;
    @Nonnull
    private State state = State.WAITING;
    // private SortedSet<Piece> rarest;
    private BitSet completedPieces = new BitSet();
    private int blockLength = PieceHandler.DEFAULT_BLOCK_SIZE;
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
    public TorrentHandler(@Nonnull Client client, @Nonnull byte[] torrent, @Nonnull File destDir)
            throws IOException, URISyntaxException {
        this(client, new Torrent(torrent), destDir);
    }

    public TorrentHandler(@Nonnull Client client, @Nonnull File torrent, @Nonnull File destDir)
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
    public TorrentHandler(@Nonnull Client client, @Nonnull Torrent torrent, @Nonnull File destDir)
            throws IOException {
        this(client, torrent, toStorage(torrent, destDir));
    }

    @Nonnull
    private static TorrentByteStorage toStorage(@Nonnull Torrent torrent, @Nonnull File parent)
            throws IOException {
        Preconditions.checkNotNull(parent, "Parent directory was null.");

        String parentPath = parent.getCanonicalPath();

        if (!torrent.isMultifile() && parent.isFile())
            return new FileStorage(parent, torrent.getSize());

        List<FileStorage> files = new LinkedList<FileStorage>();
        long offset = 0L;
        for (Torrent.TorrentFile file : torrent.getFiles()) {
            // TODO: Files.simplifyPath() is a security check here to avoid jail-escape.
            // However, it uses "/" not File.separator internally.
            String path = Files.simplifyPath("/" + file.path);
            File actual = new File(parent, path);
            String actualPath = actual.getCanonicalPath();
            if (!actualPath.startsWith(parentPath))
                throw new SecurityException("Torrent file path attempted to break directory jail: " + actualPath + " is not within " + parentPath);

            FileUtils.forceMkdir(actual.getParentFile());
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
    public TorrentHandler(@Nonnull Client client, @Nonnull Torrent torrent, @Nonnull TorrentByteStorage bucket) {
        this.client = client;
        this.torrent = torrent;
        this.bucket = bucket;
        this.trackerHandler = new TrackerHandler(client, this);
        this.swarmHandler = new SwarmHandler(this);
    }

    @Nonnull
    public Client getClient() {
        return client;
    }

    @Nonnull
    private String getLocalPeerName() {
        return getClient().getEnvironment().getLocalPeerName();
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

    @Override
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

    @Nonnegative
    public long getPieceOffset(@Nonnegative int index) {
        return (long) index * (long) torrent.getPieceLength();
    }

    /**
     * @see Torrent#getPieceLength(int)
     */
    @Nonnegative
    public int getPieceLength(@Nonnegative int index) {
        return getTorrent().getPieceLength(index);
    }

    @Nonnegative
    public int getBlockLength() {
        return blockLength;
    }

    public void setBlockLength(@Nonnegative int blockLength) {
        this.blockLength = blockLength;
    }

    @Override
    public List<? extends List<? extends URI>> getAnnounceList() {
        return getTorrent().getAnnounceList();
    }

    @Nonnull
    public TrackerHandler getTrackerHandler() {
        return trackerHandler;
    }

    @Nonnull
    public SwarmHandler getSwarmHandler() {
        return swarmHandler;
    }

    @Override
    public void addPeers(Iterable<? extends SocketAddress> peerAddresses) {
        getSwarmHandler().addPeers(peerAddresses);
    }

    @Nonnull
    public State getState() {
        synchronized (lock) {
            return state;
        }
    }

    public void setState(@Nonnull State state) {
        synchronized (lock) {
            this.state = state;
        }
        getClient().fireTorrentState(this, state);
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

    @Override
    public long getUploaded() {
        return swarmHandler.getUploaded();
    }

    @Override
    public long getDownloaded() {
        return swarmHandler.getDownloaded();
    }

    /**
     * Get the number of bytes left to download for this torrent.
     */
    @Override
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
    @VisibleForTesting
    /* pp */ void init() throws InterruptedException, IOException {
        {
            State s = getState();
            if (s != State.WAITING) {
                LOG.info("Restarting torrent from state " + s);
                return;
            }
        }
        setState(State.VALIDATING);

        try {
            int npieces = torrent.getPieceCount();

            long size = getSize();
            // Store in a local so we can update with minimal synchronization.
            BitSet completedPieces = new BitSet(npieces);
            long completedSize = 0;

            ThreadPoolExecutor executor = client.getEnvironment().getExecutorService();
            // TorrentCreator.newExecutor("TorrentHandlerInit");
            try {
                LOG.info("{}: Analyzing local data for {} ({} pieces)...", new Object[]{
                    getLocalPeerName(), getName(), npieces
                });

                int step = 10;
                CountDownLatch latch = new CountDownLatch(npieces);
                for (int index = 0; index < npieces; index++) {
                    // TODO: Read the file sequentially and pass it to the validator.
                    // Otherwise we thrash the disk on validation.
                    ByteBuffer buffer = ByteBuffer.allocate(getPieceLength(index));
                    bucket.read(buffer, getPieceOffset(index));
                    buffer.flip();
                    executor.execute(new PieceValidator(torrent, index, buffer, completedPieces, latch));

                    if (index / (float) npieces * 100f > step) {
                        LOG.info("{}:  ... {}% complete", getLocalPeerName(), step);
                        step += 10;
                    }
                }
                latch.await();

                for (int i = completedPieces.nextSetBit(0); i >= 0;
                        i = completedPieces.nextSetBit(i + 1)) {
                    completedSize += getPieceLength(i);
                }
            } finally {
                // Request orderly executor shutdown and wait for hashing tasks to
                // complete.
                // executor.shutdown();
                // executor.awaitTermination(1, TimeUnit.SECONDS);
            }

            LOG.debug("{}: {}: we have {}/{} bytes ({}%) [{}/{} pieces].",
                    new Object[]{
                getLocalPeerName(), getName(),
                completedSize, size,
                String.format("%.1f", (100f * (completedSize / (float) size))),
                completedPieces.cardinality(),
                getPieceCount()
            });

            synchronized (lock) {
                this.completedPieces = completedPieces;
            }

            if (isComplete())
                setState(State.SEEDING);
            else
                setState(State.SHARING);
        } catch (Exception e) {
            setState(State.ERROR);
            Throwables.propagateIfPossible(e, InterruptedException.class, IOException.class);
            throw Throwables.propagate(e);
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
    public void info(boolean verbose) {
        getSwarmHandler().info(verbose);
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
        if (!isInitialized())
            throw new IllegalStateException("Torrent not yet initialized!");
        if (!isComplete())
            throw new IllegalStateException("Torrent download is not complete!");

        bucket.finish();
        trackerHandler.complete();
        setState(State.SEEDING);
    }

    public boolean isFinished() {
        return isComplete() && bucket.isFinished();
    }

    /**
     * Tells whether this torrent has been fully downloaded, or is fully
     * available locally.
     */
    public boolean isComplete() {
        return getCompletedPieceCount() == getPieceCount();
    }

    public void close() throws IOException {
        // Determine final state
        if (isFinished())
            setState(State.DONE);
        else
            setState(State.ERROR);

        this.bucket.close();
    }

    public void start() throws InterruptedException, IOException {
        init();
        trackerHandler.start();
        swarmHandler.start();
    }

    public void stop() {
        trackerHandler.stop();
        swarmHandler.stop();
    }

    @Override
    public String toString() {
        return TorrentUtils.toHex(getInfoHash()) + " [" + getCompletedPieceCount() + "/" + getPieceCount() + "]";
    }
}