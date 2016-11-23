package com.turn.ttorrent.testutil;

public abstract class WaitFor {

	private static final long DEFAULT_WAIT_TIMEOUT = 40 * 1000;

	private static final long DEFAULT_POLL_INTERVAL = 100;

	protected WaitFor() {
		this(DEFAULT_WAIT_TIMEOUT);
	}

	protected WaitFor(long timeout) {
		this(timeout, DEFAULT_POLL_INTERVAL);
	}

	protected WaitFor(long timeout, long interval) {
		long started = System.currentTimeMillis();
		try {
			while (!condition() && System.currentTimeMillis() - started < timeout) {
				Thread.sleep(interval);
			}
		} catch (InterruptedException ignored) {
		}
	}

	protected abstract boolean condition();
}
