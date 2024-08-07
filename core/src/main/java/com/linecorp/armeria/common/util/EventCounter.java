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

package com.linecorp.armeria.common.util;

import static java.util.Objects.requireNonNull;

import java.time.Duration;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A counter that counts events as success or failure.
 */
@UnstableApi
public interface EventCounter {

    /**
     * Returns a new {@link EventCounter} that counts events within a sliding window.
     */
    static EventCounter ofSlidingWindow(Ticker ticker, Duration slidingWindow, Duration updateInterval) {
        requireNonNull(ticker, "ticker");
        requireNonNull(slidingWindow, "slidingWindow");
        requireNonNull(updateInterval, "updateInterval");
        return new SlidingWindowCounter(ticker, slidingWindow, updateInterval);
    }

    /**
     * Returns a new {@link EventCounter} that counts events within a sliding window.
     */
    static EventCounter ofSlidingWindow(Duration slidingWindow, Duration updateInterval) {
        return ofSlidingWindow(Ticker.systemTicker(), slidingWindow, updateInterval);
    }

    /**
     * Returns the current {@link EventCount}.
     */
    EventCount count();

    /**
     * Counts success events.
     *
     * @return the current {@link EventCount} if it has been updated, or {@code null} otherwise.
     */
    @Nullable
    EventCount onSuccess();

    /**
     * Counts failure events.
     *
     * @return the current {@link EventCount} if it has been updated, or {@code null} otherwise.
     */
    @Nullable
    EventCount onFailure();
}
