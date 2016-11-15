package com.turn.ttorrent.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.*;

/**
 * Hashes the torrent's file pieces
 *
 * @author rzanella
 */
public class ChunkHasher {

    private static final Logger logger = LoggerFactory.getLogger(ChunkHasher.class);

    private final ExecutorService executor;

    /**
     * Matches the number of threads
     */
    private final ArrayBlockingQueue<MessageDigest> mdQueue;

    /**
     * A limited pool of buffers, so that:
     *
     *  - We don't thrash the memory with a lot of short-lived objects
     *  - We don't use a lot of memory when we're ingesting a huge amount of data
     *
     * The ByteBuffers are array backed, so the APIs they get sent to have no need to instantiate one
     */

    private final ArrayBlockingQueue<ByteBuffer> bbQueue;

    /**
     * Creates the resources needed to hash the enqueued pieces
     *
     * @param threads number of workers to create
     * @param pieceLength size of the pieces that will be received, has to be informed upon creation since
     *                    the user will get the buffer from here
     */
    public ChunkHasher(int threads, int pieceLength) throws InterruptedException, NoSuchAlgorithmException {
        mdQueue = new ArrayBlockingQueue<MessageDigest>(threads);

        for (int i = 0; i < threads; i++) {
            mdQueue.add(MessageDigest.getInstance("SHA-1"));
        }

        bbQueue = new ArrayBlockingQueue<ByteBuffer>(threads + 1);

        for (int i = 0; i < threads + 1; i++) {
            bbQueue.add(ByteBuffer.allocate(pieceLength));
        }

        executor = Executors.newFixedThreadPool(threads);
    }

    /**
     *
     * @param buffer
     * @return Future so that the user can order the results on it's side
     * @throws NoSuchAlgorithmException
     */
    public Future<String> enqueueChunk(ByteBuffer buffer) throws NoSuchAlgorithmException {
        return executor.submit(new CallableChunkHasher(buffer));
    }

    /**
     *
     * @return an array-backed ByteBuffer of pieceLength size
     * @throws InterruptedException
     */
    public ByteBuffer getBuffer() throws InterruptedException {
        return bbQueue.take();
    }

    /**
     * Clears the internal resources
     *
     * @throws InterruptedException
     */
    public void shutdown() throws InterruptedException {
        // Request orderly executor shutdown and wait for hashing tasks to
        // complete.
        executor.shutdown();
        while (!executor.isTerminated()) {
            Thread.sleep(10);
        }
    }

    /**
     * A {@link Callable} to hash a data chunk.
     *
     * @author mpetazzoni
     */
    private class CallableChunkHasher implements Callable<String> {

        private final ByteBuffer data;

        CallableChunkHasher(ByteBuffer rentedBuffer) throws NoSuchAlgorithmException {
            this.data = rentedBuffer;
        }

        @Override
        public String call() throws UnsupportedEncodingException, InterruptedException {
            final MessageDigest md = mdQueue.remove();

            this.data.mark();
            this.data.reset();
            md.update(this.data);

            final String hash = new String(md.digest(), Torrent.BYTE_ENCODING);

            this.data.clear();
            bbQueue.add(this.data);

            md.reset();
            mdQueue.add(md);

            return hash;
        }
    }
}
