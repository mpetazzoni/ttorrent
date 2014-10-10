/*
 * Copyright 2013 shevek.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.turn.ttorrent.common;

import com.turn.ttorrent.bcodec.BEValue;
import io.netty.util.concurrent.DefaultThreadFactory;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Helper class to create a {@link Torrent} object for a set of files.
 *
 * <p>
 * Hash the given files to create the multi-file {@link Torrent} object
 * representing the Torrent meta-info about them, needed for announcing
 * and/or sharing these files. Since we created the torrent, we're
 * considering we'll be a full initial seeder for it.
 * </p>
 *
 * @param parent The parent directory or location of the torrent files,
 * also used as the torrent's name.
 * @param files The files to add into this torrent.
 * @param announce The announce URI that will be used for this torrent.
 * @param announceList The announce URIs organized as tiers that will 
 * be used for this torrent
 * @param createdBy The creator's name, or any string identifying the
 * torrent's creator.
 *
 * @author shevek
 */
public class TorrentCreator {

    private static final Logger logger = LoggerFactory.getLogger(TorrentCreator.class);
    public static final int DEFAULT_PIECE_LENGTH = 512 * 1024;

    /**
     * Determine how many threads to use for the piece hashing.
     *
     * <p>
     * If the environment variable TTORRENT_HASHING_THREADS is set to an
     * integer value greater than 0, its value will be used. Otherwise, it
     * defaults to the number of processors detected by the Java Runtime.
     * </p>
     *
     * @return How many threads to use for concurrent piece hashing.
     */
    protected static int getHashingThreadsCount() {
        String threads = System.getenv("TTORRENT_HASHING_THREADS");

        if (threads != null) {
            try {
                int count = Integer.parseInt(threads);
                if (count > 0) {
                    return count;
                }
            } catch (NumberFormatException nfe) {
                // Pass
            }
        }

        return Runtime.getRuntime().availableProcessors();
    }

    /**
     * Creates a new executor suitable for torrent hashing.
     * 
     * This executor controls memory usage by using a bounded queue, and the
     * CallerRunsPolicy slows down the producer if the queue bound is exceeded.
     * The requirement is then to make the queue large enough to keep all the
     * executor threads busy if the producer executes a task itself.
     * 
     * In terms of memory, Executor.execute is much more efficient than
     * ExecutorService.submit, and ByteBuffer(s) released by the ChunkHasher(s)
     * remain in eden space, so are rapidly recycled for reading the next
     * block(s). JVM ergonomics can make this much more efficient than any
     * heap-based strategy we might devise. Now, we want the queue size small
     * enough that JVM ergonomics keeps the eden size small.
     */
    @Nonnull
    public static ThreadPoolExecutor newExecutor(@Nonnull String peerName) {
        int threads = getHashingThreadsCount();
        logger.info("Creating ExecutorService with {} threads", new Object[]{threads});
        ThreadFactory factory = new DefaultThreadFactory("bittorrent-executor-" + peerName, true);
        ThreadPoolExecutor service = new ThreadPoolExecutor(0, threads,
                1L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(threads * 3),
                factory,
                new ThreadPoolExecutor.CallerRunsPolicy());
        service.allowCoreThreadTimeOut(true);
        return service;
    }
    private Executor executor;
    private final File parent;
    private List<File> files;
    private int pieceLength = DEFAULT_PIECE_LENGTH;
    private List<List<URI>> announce = new ArrayList<List<URI>>();
    private String createdBy = getClass().getName();

    /*
     * @param parent The parent directory or location of the torrent files,
     * also used as the torrent's name.
     */
    public TorrentCreator(@Nonnull File parent) {
        this.parent = parent;
    }

    public void setExecutor(@Nonnull Executor executor) {
        this.executor = executor;
    }

    /*
     * @param files The files to add into this torrent.
     */
    public void setFiles(@Nonnull List<File> files) {
        this.files = files;
    }

    public void setPieceLength(@Nonnegative int pieceLength) {
        this.pieceLength = pieceLength;
    }

    public void setAnnounceList(@Nonnull List<URI> announce) {
        setAnnounceTiers(Arrays.asList(announce));
    }

    public void setAnnounceTiers(@Nonnull List<List<URI>> announce) {
        this.announce = announce;
    }

    /*
     * @param announce The announce URIs organized as tiers that will 
     * be used for this torrent.
     */
    public void setAnnounce(@Nonnull URI... announce) {
        setAnnounceList(Arrays.asList(announce));
    }

    /*
     * @param createdBy The creator's name, or any string identifying the
     * torrent's creator.
     */
    public void setCreatedBy(@Nonnull String createdBy) {
        this.createdBy = createdBy;
    }

    private void validate(@Nonnull File file) {
        if (!file.isFile())
            throw new IllegalStateException("Not a file: " + file);
        if (!file.canRead())
            throw new IllegalStateException("Not readable: " + file);
    }

    private void validate() {
        if (executor == null)
            executor = newExecutor("TorrentCreator-" + parent);
        if (files == null) {
            validate(parent);
        } else {
            for (File file : files)
                validate(file);
        }
    }

    /**
     * Helper method to create a {@link Torrent} object for a set of files.
     *
     * <p>
     * Hash the given files to create the {@link Torrent} object
     * representing the Torrent meta-info about them, needed for announcing
     * and/or sharing these files.
     * </p>
     */
    @Nonnull
    public Torrent create() throws InterruptedException, IOException, URISyntaxException {
        validate();

        if (files == null || files.isEmpty())
            logger.info("Creating single-file torrent for {}...", parent.getName());
        else
            logger.info("Creating {}-file torrent {}...", files.size(), parent.getName());

        Map<String, BEValue> torrent = new HashMap<String, BEValue>();

        ANNOUNCE:
        if (announce != null) {
            List<URI> announceFlat = new ArrayList<URI>();
            List<BEValue> announceTiers = new LinkedList<BEValue>();
            for (List<? extends URI> trackers : announce) {
                List<BEValue> announceTier = new LinkedList<BEValue>();
                for (URI tracker : trackers) {
                    announceFlat.add(tracker);
                    announceTier.add(new BEValue(tracker.toString()));
                }
                if (!announceTier.isEmpty())
                    announceTiers.add(new BEValue(announceTier));
            }
            if (announceFlat.size() == 1)
                torrent.put("announce", new BEValue(announceFlat.get(0).toString()));
            if (!announceTiers.isEmpty())
                torrent.put("announce-list", new BEValue(announceTiers));
        }

        torrent.put("creation date", new BEValue(new Date().getTime() / 1000));
        torrent.put("created by", new BEValue(createdBy));

        Map<String, BEValue> info = new TreeMap<String, BEValue>();
        info.put("name", new BEValue(parent.getName()));
        info.put("piece length", new BEValue(pieceLength));

        if (files == null || files.isEmpty()) {
            long nbytes = parent.length();
            info.put("length", new BEValue(nbytes));
            info.put("pieces", new BEValue(hashFiles(executor, Arrays.asList(parent), nbytes, pieceLength)));
        } else {
            List<BEValue> fileInfo = new LinkedList<BEValue>();
            long nbytes = 0L;
            for (File file : files) {
                Map<String, BEValue> fileMap = new HashMap<String, BEValue>();
                long length = file.length();
                fileMap.put("length", new BEValue(length));
                nbytes += length;

                LinkedList<BEValue> filePath = new LinkedList<BEValue>();
                while (file != null && !parent.equals(file)) {
                    filePath.addFirst(new BEValue(file.getName()));
                    file = file.getParentFile();
                }

                fileMap.put("path", new BEValue(filePath));
                fileInfo.add(new BEValue(fileMap));
            }
            info.put("files", new BEValue(fileInfo));
            info.put("pieces", new BEValue(hashFiles(executor, files, nbytes, pieceLength)));
        }
        torrent.put("info", new BEValue(info));

        return new Torrent(torrent);
    }

    /**
     * A {@link Runnable} to hash a data chunk.
     *
     * @author mpetazzoni
     */
    private static class ChunkHasher implements Runnable {

        private final ThreadLocal<MessageDigest> digest = new ThreadLocal<MessageDigest>() {
            @Override
            protected MessageDigest initialValue() {
                return DigestUtils.getSha1Digest();
            }
        };
        private final byte[] out;
        private final int piece;
        private final CountDownLatch latch;
        private final ByteBuffer data;

        ChunkHasher(@Nonnull byte[] out, @Nonnegative int piece, @Nonnull CountDownLatch latch, @Nonnull ByteBuffer data) {
            this.out = out;
            this.piece = piece;
            this.latch = latch;
            this.data = data;
        }

        @Override
        public void run() {
            try {
                MessageDigest digest = this.digest.get();
                digest.update(this.data);
                System.arraycopy(digest.digest(), 0, out, piece * Torrent.PIECE_HASH_SIZE, Torrent.PIECE_HASH_SIZE);
            } finally {
                latch.countDown();
            }
        }
    }

    /**
     * Return the concatenation of the SHA-1 hashes of a file's pieces.
     *
     * <p>
     * Hashes the given file piece by piece using the default Torrent piece
     * length (see {@link #PIECE_LENGTH}) and returns the concatenation of
     * these hashes, as a string.
     * </p>
     *
     * <p>
     * This is used for creating Torrent meta-info structures from a file.
     * </p>
     *
     * @param files The file to hash.
     */
    public /* for testing */ static byte[] hashFiles(Executor executor, List<File> files, long nbytes, int pieceLength)
            throws InterruptedException, IOException {
        int npieces = (int) Math.ceil((double) nbytes / pieceLength);
        byte[] out = new byte[Torrent.PIECE_HASH_SIZE * npieces];
        CountDownLatch latch = new CountDownLatch(npieces);

        ByteBuffer buffer = ByteBuffer.allocate(pieceLength);

        long start = System.nanoTime();
        int piece = 0;
        for (File file : files) {
            logger.info("Hashing data from {} ({} pieces)...", new Object[]{
                file.getName(),
                (int) Math.ceil((double) file.length() / pieceLength)
            });

            FileInputStream fis = FileUtils.openInputStream(file);
            FileChannel channel = fis.getChannel();
            int step = 10;

            try {
                while (channel.read(buffer) > 0) {
                    if (buffer.remaining() == 0) {
                        buffer.flip();
                        executor.execute(new ChunkHasher(out, piece, latch, buffer));
                        buffer = ByteBuffer.allocate(pieceLength);
                        piece++;
                    }

                    if (channel.position() / (double) channel.size() * 100f > step) {
                        logger.info("  ... {}% complete", step);
                        step += 10;
                    }
                }
            } finally {
                channel.close();
                fis.close();
            }
        }

        // Hash the last bit, if any
        if (buffer.position() > 0) {
            buffer.flip();
            executor.execute(new ChunkHasher(out, piece, latch, buffer));
            piece++;
        }

        // Wait for hashing tasks to complete.
        latch.await();
        long elapsed = System.nanoTime() - start;

        logger.info("Hashed {} file(s) ({} bytes) in {} pieces ({} expected) in {}ms.",
                new Object[]{
            files.size(),
            nbytes,
            piece,
            npieces,
            String.format("%.1f", elapsed / 1e6)
        });

        return out;
    }
}