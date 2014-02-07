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
import java.io.Serializable;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;

/**
 *
 * @author shevek
 */
public abstract class RateComparator implements Comparator<PeerHandler>, Serializable {

    private static final long serialVersionUID = 1L;

    private static int compare(@Nonnull Rate a, @Nonnull Rate b) {
        return Double.compare(a.rate(TimeUnit.SECONDS), b.rate(TimeUnit.SECONDS));
    }

    /**
     * Download rate comparator.
     *
     * <p>
     * Compares sharing peers based on their current download rate.
     * </p>
     *
     * @author mpetazzoni
     */
    public static class DLRateComparator extends RateComparator {

        private static double rate(PeerHandler peer) {
            double rate = peer.getDLRate().rate(TimeUnit.SECONDS);
            rate += peer.getRequestsSentCount();
            if (!peer.isChoked(0))
                rate = (rate + 100) * 1.5;
            return rate;
        }

        @Override
        public int compare(PeerHandler a, PeerHandler b) {
            double ra = rate(a);
            double rb = rate(b);
            return Double.compare(rb, ra);
        }
    }

    /**
     * Upload rate comparator.
     *
     * <p>
     * Compares sharing peers based on their current upload rate.
     * </p>
     *
     * @author mpetazzoni
     */
    public static class ULRateComparator extends RateComparator {

        private static double rate(PeerHandler peer) {
            double rate = peer.getDLRate().rate(TimeUnit.SECONDS);
            if (!peer.isChoked(0))
                rate = (rate + 100) * 1.5;
            return rate;
        }

        @Override
        public int compare(PeerHandler a, PeerHandler b) {
            double ra = rate(a);
            double rb = rate(b);
            return Double.compare(rb, ra);
        }
    }
}
