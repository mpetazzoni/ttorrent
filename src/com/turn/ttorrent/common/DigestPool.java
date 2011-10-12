
package com.turn.ttorrent.common;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Map;
import java.util.List;
import java.util.LinkedList;
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.Callable;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.nio.ByteBuffer;

import java.security.DigestException;
import java.io.UnsupportedEncodingException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DigestPool {
	private static final Logger logger = LoggerFactory.getLogger(DigestPool.class);

	private int numThreads;
	private int bufferSize;
	private BlockingQueue<Runnable> queue;
	private BitSet digestSet;
	private List<Digester> digesters = new LinkedList<Digester>();
	private List<Future<HashedPiece>> tasks = new ArrayList<Future<HashedPiece>>();
	private Map<Integer, String> hashes = new TreeMap<Integer, String>();
	private ThreadPoolExecutor executor;
	private AtomicLong hashing = new AtomicLong(0L);

	public static class HashedPiece {
		public int index;
		public String hash;
	}

	public static class BufferPiece {
		public int index;
		public int offset;
		public int length;

		public BufferPiece(int index, int offset, int length) {
			this.index = index; this.offset = offset; this.length = length;
		}
	}

	public static class Digester {
		public ByteBuffer buffer;
		public byte[] bytes;
		public boolean buffered;
		public SHA sha1;
		public int index;
		public int offset;
		public int length;

		public String hash() throws UnsupportedEncodingException, DigestException {
			sha1.reset();
			if (buffered) {
				logger.trace(String.format("buffer: index %s, offset %s, length %s", index, offset, length));
				sha1.update(buffer.array(), offset, length);
			} else {
				logger.trace(String.format(" bytes: index %s, offset %s, length %s", index, offset, length));
				sha1.update(bytes, offset, length);
			}
			String ret = new String(sha1.digest(), Torrent.BYTE_ENCODING);
			return ret; 
		}
	}

	public DigestPool(int numThreads, int bufferSize) {
		this.numThreads = numThreads;
		this.queue = new LinkedBlockingQueue<Runnable>(numThreads);
		this.digestSet = new BitSet(numThreads);
		this.bufferSize = bufferSize;

		// create our digesters
		for (int i = 0; i < numThreads; i++) {
			Digester digester = new Digester();
			digester.sha1 = new SHA();
			this.digesters.add(digester);
		}
		this.executor = new ThreadPoolExecutor(numThreads, numThreads, 2L, TimeUnit.SECONDS, this.queue);
	}

	public int numThreads() {
		return this.numThreads;
	}

	public long hashing() {
		return hashing.get();
	}

	public List<BufferPiece> getPieces(final int index, int length) {
		int offset = 0;
		int idx = index;
		List<BufferPiece> pieces = new LinkedList<BufferPiece>();
		for (int i = 0; i < numThreads; i++) {
			if (offset > length) break;
			int end = offset + bufferSize;
			if (end > length) end = length;
			int len = end - offset;

			pieces.add(new BufferPiece(idx, offset, len));

			offset += bufferSize;
			idx += 1;
		}
		return pieces;
	}

	public void process() throws Exception {
		for (Future<HashedPiece> task : tasks) {
			HashedPiece hp = task.get();
			hashes.put(hp.index, hp.hash);
			logger.trace(String.format("got back index %s", hp.index));
		}
		tasks.clear();
	}

	private void createTask(final Digester digester) throws Exception {
		Callable<HashedPiece> hasher = new Callable<HashedPiece>() {
			@Override
			public HashedPiece call() throws Exception {
				HashedPiece hp = new HashedPiece();
				long start = System.currentTimeMillis();
				hp.hash  = digester.hash();
				hashing.getAndAdd(System.currentTimeMillis() - start);
				hp.index = digester.index;
				free(digester);
				return hp;
			}
		};
		try {
			Future<HashedPiece> task = executor.submit(hasher);
			tasks.add(task);
		} catch (RejectedExecutionException e) {
			logger.error("not submitted...", e);
			throw new Exception("couldn't submit digest task");
		}
	}

	public void submit(final int index, byte[] bytes, int length) throws Exception {
		List<BufferPiece> pieces = getPieces(index, length);
		for (BufferPiece piece : pieces) {
			digest(piece.index, piece.offset, piece.length, bytes);
		}
		process();
	}

	public void submit(final int index, ByteBuffer buffer, int length) throws Exception {
		List<BufferPiece> pieces = getPieces(index, length);
		for (BufferPiece piece : pieces) {
			digest(piece.index, piece.offset, piece.length, buffer);
		}
		process();
	}

	public void digest(final int index, int offset, int length, ByteBuffer buffer) throws Exception {
		final Digester digester = pick(index, offset, length);
		digester.buffer = buffer;
		digester.buffered = true;
		createTask(digester);
	}

	public void digest(final int index, int offset, int length, byte[] bytes) throws Exception {
		final Digester digester = pick(index, offset, length);
		digester.bytes = bytes;
		digester.buffered = false;
		createTask(digester);
	}

	public void stop() {
		this.executor.shutdown();
	}

	public String getHashes() throws InterruptedException, ExecutionException {
		this.executor.shutdown();
		StringBuffer sb = new StringBuffer();
		for (Map.Entry<Integer,String> hash: hashes.entrySet()) {
			sb.append(hash.getValue());
		}
		logger.debug("hashed {} pieces in parallel", hashes.size());
		return sb.toString();
	}

	public Map<Integer, String> getHashedPieces() throws InterruptedException, ExecutionException {
		this.executor.shutdown();
		logger.debug("hashed {} pieces in parallel", hashes.size());
		return hashes;
	}

	public Digester pick(final int index, final int offset, final int length) throws Exception {
		Digester digester;
		int idx;
		synchronized (this.digestSet) {
			idx = digestSet.nextClearBit(0);
			digestSet.set(idx);
		}
		digester = digesters.get(idx);
		digester.index = index;
		digester.offset = offset;
		digester.length = length;
		return digester;
	}

	public void free(Digester digester) {
		int idx = digesters.indexOf(digester);
		synchronized (this.digestSet) {
			digestSet.clear(idx);
		}
	}

	public static String bufferToString(ByteBuffer buf) {
		byte[] byteArray = new byte[buf.remaining()];
		buf.get(byteArray);
		return new String(byteArray);
	}
}
