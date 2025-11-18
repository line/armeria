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

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.internal.testing.TestTicker;

class OutlierDetectionTest {

    @Test
    void testFailureRate() {
        final Duration slidingWindow = Duration.ofSeconds(1);
        final Duration updateInterval = Duration.ofMillis(100);
        final int minimumRequestThreshold = 10;
        final TestTicker ticker = new TestTicker();
        final double failureRateThreshold = 0.5;
        final OutlierDetection detection =
                OutlierDetection.builder(OutlierRule.of())
                                .counterSlidingWindow(slidingWindow)
                                .counterUpdateInterval(updateInterval)
                                .failureRateThreshold(failureRateThreshold)
                                .minimumRequestThreshold(minimumRequestThreshold)
                                .ticker(ticker)
                                .build();
        final OutlierDetector detector = detection.newDetector();

        for (int i = 0; i < minimumRequestThreshold; i++) {
            detector.onSuccess();
        }
        for (int i = 0; i < minimumRequestThreshold + 1; i++) {
            detector.onFailure();
        }
        assertThat(detector.isOutlier()).isFalse();
        ticker.advance(slidingWindow);
        detector.onFailure();
        assertThat(detector.isOutlier()).isTrue();
        detector.onSuccess();
        assertThat(detector.isOutlier()).isTrue();

        ticker.advance(slidingWindow);
        detector.onSuccess();
        // Unlike a circuit breaker, once it is marked as an outlier, it cannot be restored after
        // the window interval.
        assertThat(detector.isOutlier()).isTrue();
    }

    @Test
    void resetAfterSlidingWindow() {
        final Duration slidingWindow = Duration.ofSeconds(2);
        final Duration updateInterval = Duration.ofMillis(1000);
        final int minimumRequestThreshold = 20;
        final TestTicker ticker = new TestTicker();
        final double failureRateThreshold = 0.51;
        final OutlierDetection detection =
                OutlierDetection.builder(OutlierRule.of())
                                .counterSlidingWindow(slidingWindow)
                                .counterUpdateInterval(updateInterval)
                                .failureRateThreshold(failureRateThreshold)
                                .minimumRequestThreshold(minimumRequestThreshold)
                                .ticker(ticker)
                                .build();
        final OutlierDetector detector = detection.newDetector();
        for (int i = 0; i < 5; i++) {
            for (int j = 0; j < minimumRequestThreshold / 2; j++) {
                detector.onSuccess();
                detector.onFailure();
            }
            // The failure rate hasn't exceeded the threshold.
            assertThat(detector.isOutlier()).isFalse();
            ticker.advance(slidingWindow);
        }
    }
}
