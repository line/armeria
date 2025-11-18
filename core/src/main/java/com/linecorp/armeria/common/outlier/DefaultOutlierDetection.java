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
import java.util.Objects;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.util.Ticker;

final class DefaultOutlierDetection implements OutlierDetection {

    private final OutlierRule rule;
    private final Ticker ticker;
    private final Duration counterSlidingWindow;
    private final Duration counterUpdateInterval;
    private final double failureRateThreshold;
    private final long minimumRequestThreshold;

    DefaultOutlierDetection(OutlierRule rule, Ticker ticker,
                            Duration counterSlidingWindow, Duration counterUpdateInterval,
                            double failureRateThreshold, long minimumRequestThreshold) {
        this.rule = rule;
        this.ticker = ticker;
        this.counterSlidingWindow = counterSlidingWindow;
        this.counterUpdateInterval = counterUpdateInterval;
        this.failureRateThreshold = failureRateThreshold;
        this.minimumRequestThreshold = minimumRequestThreshold;
    }

    @Override
    public OutlierRule rule() {
        return rule;
    }

    @Override
    public OutlierDetector newDetector() {
        return new DefaultOutlierDetector(ticker, counterSlidingWindow, counterUpdateInterval,
                                          failureRateThreshold, minimumRequestThreshold);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DefaultOutlierDetection)) {
            return false;
        }
        final DefaultOutlierDetection that = (DefaultOutlierDetection) o;
        return Double.compare(failureRateThreshold, that.failureRateThreshold) == 0 &&
               minimumRequestThreshold == that.minimumRequestThreshold &&
               counterSlidingWindow.equals(that.counterSlidingWindow) &&
               counterUpdateInterval.equals(that.counterUpdateInterval) &&
               rule.equals(that.rule) &&
               ticker.equals(that.ticker);
    }

    @Override
    public int hashCode() {
        return Objects.hash(rule, ticker, counterSlidingWindow, counterUpdateInterval,
                            failureRateThreshold, minimumRequestThreshold);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("rule", rule)
                          .add("ticker", ticker)
                          .add("counterSlidingWindow", counterSlidingWindow)
                          .add("counterUpdateInterval", counterUpdateInterval)
                          .add("failureRateThreshold", failureRateThreshold)
                          .add("minimumRequestThreshold", minimumRequestThreshold)
                          .toString();
    }
}
