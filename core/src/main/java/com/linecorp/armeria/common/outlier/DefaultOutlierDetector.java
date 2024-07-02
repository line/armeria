/*
 * Copyright 2024 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.common.outlier;

import java.time.Duration;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.EventCount;
import com.linecorp.armeria.common.util.EventCounter;
import com.linecorp.armeria.common.util.Ticker;

final class DefaultOutlierDetector implements OutlierDetector {

    private final EventCounter counter;
    private final double failureRateThreshold;
    private final long minimumRequestThreshold;
    private volatile boolean isOutlier;

    DefaultOutlierDetector(Ticker ticker, Duration slidingWindow, Duration updateInterval,
                           double failureRateThreshold, long minimumRequestThreshold) {
        counter = EventCounter.ofSlidingWindow(ticker, slidingWindow, updateInterval);
        this.failureRateThreshold = failureRateThreshold;
        this.minimumRequestThreshold = minimumRequestThreshold;
    }

    @Override
    public void onSuccess() {
        if (isOutlier) {
            // We don't need to count success events if the target is already marked as an outlier.
            return;
        }
        checkIfExceedingFailureThreshold(counter.onSuccess());
    }

    @Override
    public void onFailure() {
        if (isOutlier) {
            // We don't need to count failure events if the target is already marked as an outlier.
            return;
        }
        checkIfExceedingFailureThreshold(counter.onFailure());
    }

    @Override
    public boolean isOutlier() {
        return isOutlier;
    }

    private void checkIfExceedingFailureThreshold(@Nullable EventCount count) {
        if (count == null) {
            return;
        }

        final boolean exceeds = 0 < count.total() && minimumRequestThreshold <= count.total() &&
                                failureRateThreshold < count.failureRate();
        if (exceeds) {
            isOutlier = true;
        }
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("counter", counter)
                          .add("failureRateThreshold", failureRateThreshold)
                          .add("minimumRequestThreshold", minimumRequestThreshold)
                          .add("isOutlier", isOutlier)
                          .toString();
    }
}
