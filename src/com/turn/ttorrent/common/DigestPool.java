
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

	int numThreads;
	int bufferSize;
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

	public static class Digester {
		public ByteBuffer buffer;
		public SHA sha1;
		public int index;
		public int offset;
		public int length;

		public String hash() throws UnsupportedEncodingException, DigestException {
			sha1.reset();
			logger.trace(String.format("index %s, offset %s, length %s", index, offset, length));
			logger.trace(bufferToString(ByteBuffer.wrap(buffer.array(), offset, length)));
			sha1.update(buffer.array(), offset, length);
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
			digester.buffer = ByteBuffer.allocate(bufferSize);
			digester.sha1 = new SHA();
			this.digesters.add(digester);
		}
		this.executor = new ThreadPoolExecutor(numThreads, numThreads, 2L, TimeUnit.SECONDS, this.queue);
	}

	public long hashing() {
		return hashing.get();
	}

	public void submit(final int index, ByteBuffer buffer, int length) throws Exception {
		int offset = 0;
		int idx = index;
		for (int i = 0; i < numThreads; i++) {
			if (buffer.position() > length || offset > length) break;
			int end = offset + bufferSize;
			if (end > length) end = length;
			int len = end - offset;

			digest(idx, buffer, offset, len);
			offset += bufferSize;
			idx += 1;
		}
		for (Future<HashedPiece> task : tasks) {
			HashedPiece hp = task.get();
			hashes.put(hp.index, hp.hash);
			logger.trace(String.format("got back index %s", hp.index));
		}
		tasks.clear();
	}

	private void digest(final int index, ByteBuffer buffer, int offset, int length) throws Exception {
		final Digester digester = pick(index, buffer, offset, length);
		Callable<HashedPiece> hasher = new Callable<HashedPiece>() {
			@Override
			public HashedPiece call() throws Exception {
				HashedPiece hp = new HashedPiece();
				long start = System.currentTimeMillis();
				hp.hash  = digester.hash();
				hashing.getAndAdd(System.currentTimeMillis() - start);
				hp.index = index;
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

	public String getHashes() throws InterruptedException, ExecutionException {
		this.executor.shutdown();
		StringBuffer sb = new StringBuffer();
		int count = 0;
		for (Map.Entry<Integer,String> hash: hashes.entrySet()) {
			sb.append(hash.getValue());
			count += 1;
		}
		logger.debug("got hashes: " + count);
		return sb.toString();
	}

	public Digester pick(final int index, final ByteBuffer buffer, final int offset, final int length) throws Exception {
		Digester digester;
		int idx;
		synchronized (this.digestSet) {
			idx = digestSet.nextClearBit(0);
			digestSet.set(idx);
		}
		digester = digesters.get(idx);
		digester.buffer = buffer;
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
