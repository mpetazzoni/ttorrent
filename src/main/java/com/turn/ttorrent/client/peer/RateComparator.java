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

import java.io.Serializable;
import java.util.Comparator;

/**
 *
 * @author shevek
 */
public abstract class RateComparator implements Comparator<PeerHandler>, Serializable {

    private static final long serialVersionUID = 1L;

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

        @Override
        public int compare(PeerHandler a, PeerHandler b) {
            return Rate.compare(a.getDLRate(), b.getDLRate());
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

        @Override
        public int compare(PeerHandler a, PeerHandler b) {
            return Rate.compare(a.getULRate(), b.getULRate());
        }
    }
}
