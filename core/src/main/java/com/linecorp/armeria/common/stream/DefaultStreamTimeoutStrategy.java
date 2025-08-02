/*
 * Copyright 2025 LINE Corporation
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
 *
 */

package com.linecorp.armeria.common.stream;

import static java.util.Objects.requireNonNull;

import java.time.Duration;

import com.linecorp.armeria.common.StreamTimeoutException;

/**
 * The default {@link StreamTimeoutStrategy} implementation used by
 * {@link StreamMessage#timeout(Duration, StreamTimeoutMode)}.
 *
 * <p>This strategy applies the behavior defined by the given {@link StreamTimeoutMode},
 * evaluating whether the stream has timed out based on elapsed time since the last event.
 *
 */
final class DefaultStreamTimeoutStrategy implements StreamTimeoutStrategy {

    private static final String TIMEOUT_MESSAGE = "Stream timed out after %d ms (timeout mode: %s)";

    static DefaultStreamTimeoutStrategy of(Duration timeoutDuration, StreamTimeoutMode timeoutMode) {
        requireNonNull(timeoutDuration, "timeoutDuration");
        requireNonNull(timeoutMode, "timeoutMode");
        return new DefaultStreamTimeoutStrategy(timeoutDuration, timeoutMode);
    }

    private final StreamTimeoutMode timeoutMode;

    private final Duration timeoutDuration;

    private final long timeoutNanos;

    private DefaultStreamTimeoutStrategy(Duration timeoutDuration, StreamTimeoutMode timeoutMode) {
        this.timeoutMode = timeoutMode;
        this.timeoutDuration = timeoutDuration;
        timeoutNanos = timeoutDuration.toNanos();
    }

    @Override
    public StreamTimeoutDecision initialDecision() {
        return StreamTimeoutDecision.scheduleAfter(timeoutNanos);
    }

    @Override
    public StreamTimeoutDecision evaluateTimeout(long currentTimeNanos, long lastEventTimeNanos) {
        if (timeoutMode == StreamTimeoutMode.UNTIL_EOS) {
            return StreamTimeoutDecision.TIMED_OUT;
        }

        final long elapsedNanos = currentTimeNanos - lastEventTimeNanos;
        if (timeoutNanos <= elapsedNanos) {
            return StreamTimeoutDecision.TIMED_OUT;
        }

        if (timeoutMode == StreamTimeoutMode.UNTIL_FIRST) {
            return StreamTimeoutDecision.NO_SCHEDULE;
        }

        final long delayNanos = timeoutNanos - elapsedNanos;
        return StreamTimeoutDecision.scheduleAfter(delayNanos);
    }

    @Override
    public StreamTimeoutException newTimeoutException() {
        return new StreamTimeoutException(
                String.format(TIMEOUT_MESSAGE, timeoutDuration.toMillis(), timeoutMode));
    }
}
