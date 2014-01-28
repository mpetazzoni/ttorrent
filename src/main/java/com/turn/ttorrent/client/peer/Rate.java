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

import com.yammer.metrics.stats.EWMA;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import static java.lang.Math.exp;

/**
 *
 * @author shevek
 */
public class Rate extends EWMA implements Comparable<Rate> {

    public static int compare(@Nonnull Rate a, @Nonnull Rate b) {
        return Double.compare(a.rate(TimeUnit.SECONDS), b.rate(TimeUnit.SECONDS));
    }
    public static final int INTERVAL = 5;

    public Rate(int seconds) {
        this(1 - exp(-INTERVAL / seconds), INTERVAL, TimeUnit.SECONDS);
    }

    public Rate(double alpha, long interval, TimeUnit intervalUnit) {
        super(alpha, interval, intervalUnit);
    }

    @Override
    public int compareTo(Rate o) {
        return compare(this, o);
    }

    @Override
    public String toString() {
        return rate(TimeUnit.SECONDS) + "/s";
    }
}
