/*
 * Copyright 2014 shevek.
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
package com.turn.ttorrent.client.peer;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nonnull;
import static java.lang.Math.exp;

/**
 *
 * @author shevek
 */
public class EWMA implements Comparable<EWMA> {

    public static int compare(@Nonnull EWMA a, @Nonnull EWMA b) {
        return Double.compare(a.rate(TimeUnit.SECONDS), b.rate(TimeUnit.SECONDS));
    }
    private static final int INTERVAL = 5;
    private volatile boolean initialized = false;
    private volatile double rate = 0.0;
    private final AtomicLong uncounted = new AtomicLong();
    private final double alpha, interval;

    public EWMA(int seconds) {
        this(1 - exp(-INTERVAL / seconds), INTERVAL, TimeUnit.SECONDS);
    }

    /**
     * Create a new EWMA with a specific smoothing constant.
     *
     * @param alpha        the smoothing constant
     * @param interval     the expected tick interval
     * @param intervalUnit the time unit of the tick interval
     */
    public EWMA(double alpha, long interval, TimeUnit intervalUnit) {
        this.interval = intervalUnit.toNanos(interval);
        this.alpha = alpha;
    }

    /**
     * Update the moving average with a new value.
     *
     * @param n the new value
     */
    public void update(long n) {
        uncounted.addAndGet(n);
    }

    // For notes only.
    public void schedule(ScheduledExecutorService scheduler) {
        int interval = (int) TimeUnit.NANOSECONDS.toMillis((long) this.interval);
        scheduler.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                tick();
            }
        }, interval, interval, TimeUnit.MILLISECONDS);
    }

    /**
     * Mark the passage of time and decay the current rate accordingly.
     */
    public void tick() {
        final long count = uncounted.getAndSet(0);
        final double instantRate = count / interval;
        if (initialized) {
            rate += (alpha * (instantRate - rate));
        } else {
            rate = instantRate;
            initialized = true;
        }
    }

    /**
     * Returns the rate in the given units of time.
     *
     * @param rateUnit the unit of time
     * @return the rate
     */
    public double rate(TimeUnit rateUnit) {
        return rate * (double) rateUnit.toNanos(1);
    }

    @Override
    public int compareTo(EWMA o) {
        return compare(this, o);
    }

    @Override
    public String toString() {
        return rate(TimeUnit.SECONDS) + "/s";
    }
}
