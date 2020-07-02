/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.armeria.internal.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.common.util.concurrent.Uninterruptibles;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.internal.common.DefaultTimeoutController.State;
import com.linecorp.armeria.internal.common.DefaultTimeoutController.TimeoutTask;

class DefaultTimeoutControllerTest {

    static {
        // call workerGroup early to avoid initializing contexts while testing
        CommonPools.workerGroup();
    }

    StatusCheckedTaskTimeoutController timeoutController;
    volatile boolean isTimedOut;

    @BeforeEach
    void setUp() {
        isTimedOut = false;
        final TimeoutTask timeoutTask = new TimeoutTask() {
            @Override
            public boolean canSchedule() {
                return true;
            }

            @Override
            public void run() {
                isTimedOut = true;
            }
        };
        timeoutController =
                new StatusCheckedTaskTimeoutController(
                        new DefaultTimeoutController(timeoutTask, CommonPools.workerGroup().next()));
    }

    @Test
    void shouldHaveTimeoutTask() {
        final TimeoutController emptyTaskTimeoutController =
                new DefaultTimeoutController(CommonPools.workerGroup().next());
        assertThatThrownBy(() -> emptyTaskTimeoutController.extendTimeout(100))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("setTimeoutTask(timeoutTask) is not called yet");
    }

    @Test
    void adjustTimeout() {
        final long initTimeoutNanos = Duration.ofMillis(1000).toNanos();
        final long adjustmentNanos = Duration.ofMillis(200).toNanos();
        final long tolerance = Duration.ofMillis(100).toNanos();

        timeoutController.scheduleTimeout(initTimeoutNanos);
        final long startTimeNanos = timeoutController.startTimeNanos();

        timeoutController.extendTimeout(adjustmentNanos);
        final long passedNanos = System.nanoTime() - startTimeNanos;
        assertThat(timeoutController.timeoutNanos()).isBetween(
                initTimeoutNanos + adjustmentNanos - passedNanos - tolerance,
                initTimeoutNanos + adjustmentNanos - passedNanos + tolerance);

        final long adjustmentNanos2 = Duration.ofMillis(-200).toNanos();
        timeoutController.extendTimeout(adjustmentNanos2);
        final long passedMillis2 = System.nanoTime() - startTimeNanos;
        assertThat(timeoutController.timeoutNanos()).isBetween(
                initTimeoutNanos + adjustmentNanos + adjustmentNanos2 - passedMillis2 - tolerance,
                initTimeoutNanos + adjustmentNanos + adjustmentNanos2 - passedMillis2 + tolerance);
    }

    @Test
    void resetTimeout() {
        timeoutController.scheduleTimeout(Duration.ofMillis(1000).toNanos());
        timeoutController.resetTimeout(Duration.ofMillis(500).toNanos());
        assertThat(timeoutController.timeoutNanos()).isEqualTo(Duration.ofMillis(500).toNanos());
    }

    @Test
    void resetTimeout_withoutInit() {
        timeoutController.resetTimeout(500);
        assertThat(timeoutController.timeoutNanos()).isEqualTo(500);
        assertThat((Object) timeoutController.timeoutFuture()).isNotNull();
    }

    @Test
    void resetTimout_multipleZero() {
        timeoutController.scheduleTimeout(1000);
        timeoutController.resetTimeout(0);
        timeoutController.resetTimeout(0);
    }

    @Test
    void resetTimout_multipleNonZero() {
        timeoutController.scheduleTimeout(1000);
        timeoutController.resetTimeout(0);
        timeoutController.resetTimeout(500);
    }

    @Test
    void cancelTimeout_beforeDeadline() {
        timeoutController.scheduleTimeout(1000);
        assertThat(timeoutController.cancelTimeout()).isTrue();
        assertThat(isTimedOut).isFalse();
    }

    @Test
    void cancelTimeout_afterDeadline() {
        timeoutController.scheduleTimeout(Duration.ofMillis(500).toNanos());
        Uninterruptibles.sleepUninterruptibly(1000, TimeUnit.MILLISECONDS);
        assertThat(timeoutController.cancelTimeout()).isFalse();
        assertThat(isTimedOut).isTrue();
    }

    @Test
    void cancelTimeout_byResetTimeoutZero() {
        timeoutController.scheduleTimeout(Duration.ofMillis(1000).toNanos());
        timeoutController.resetTimeout(0);
        assertThat(timeoutController.timeoutNanos()).isEqualTo(0);
        assertThat((Object) timeoutController.timeoutFuture()).isNull();
    }

    @Test
    void scheduleTimeoutWhenScheduled() {
        assertThat(timeoutController.scheduleTimeout(Duration.ofMillis(1000).toNanos())).isTrue();
        assertThat(timeoutController.scheduleTimeout(Duration.ofMillis(1000).toNanos())).isFalse();
    }

    @Test
    void scheduleTimeoutWhenTimedOut() {
        assertThat(timeoutController.timeoutNow()).isTrue();
        assertThat(timeoutController.scheduleTimeout(1000)).isFalse();
    }

    @Test
    void extendTimeoutWhenDisabled() {
        assertThat(timeoutController.extendTimeout(1000)).isFalse();
    }

    @Test
    void extendTimeoutWhenScheduled() {
        assertThat(timeoutController.scheduleTimeout(Duration.ofMillis(1000).toNanos())).isTrue();
        assertThat(timeoutController.extendTimeout(Duration.ofMillis(1000).toNanos())).isTrue();
    }

    @Test
    void extendTimeoutWhenTimedOut() {
        assertThat(timeoutController.timeoutNow()).isTrue();
        assertThat(timeoutController.extendTimeout(1000)).isFalse();
    }

    @Test
    void resetTimeoutWhenDisabled() {
        assertThat(timeoutController.resetTimeout(1000)).isTrue();
    }

    @Test
    void resetTimeoutWhenScheduled() {
        assertThat(timeoutController.scheduleTimeout(Duration.ofMillis(1000).toNanos())).isTrue();
        assertThat(timeoutController.resetTimeout(Duration.ofMillis(1000).toNanos())).isTrue();
    }

    @Test
    void resetTimeoutWhenTimedOut() {
        assertThat(timeoutController.timeoutNow()).isTrue();
        assertThat(timeoutController.resetTimeout(1000)).isFalse();
    }

    @Test
    void cancelTimeoutWhenDisabled() {
        assertThat(timeoutController.cancelTimeout()).isFalse();
    }

    @Test
    void cancelTimeoutWhenScheduled() {
        assertThat(timeoutController.scheduleTimeout(1000)).isTrue();
        assertThat(timeoutController.cancelTimeout()).isTrue();
    }

    @Test
    void cancelTimeoutWhenTimedOut() {
        assertThat(timeoutController.timeoutNow()).isTrue();
        assertThat(timeoutController.cancelTimeout()).isFalse();
    }

    @Test
    void timeoutNowWhenDisabled() {
        assertThat(timeoutController.timeoutNow()).isTrue();
    }

    @Test
    void timeoutNowWhenScheduled() {
        timeoutController.scheduleTimeout(1000);
        assertThat(timeoutController.timeoutNow()).isTrue();
    }

    @Test
    void timeoutNowWhenTimedOut() {
        timeoutController.timeoutNow();
        assertThat(timeoutController.timeoutNow()).isFalse();
    }

    @Test
    void multipleTimeoutNow() {
        assertThat(timeoutController.timeoutNow()).isTrue();
        assertThat(timeoutController.timeoutNow()).isFalse();
    }

    @Test
    void ignoreScheduledTimeoutAfterReset() {
        timeoutController.resetTimeout(1000);
        assertThat(timeoutController.scheduleTimeout(1)).isFalse();
    }

    @Test
    void disabledTimeoutTask() {
        final DefaultTimeoutController timeoutController = new DefaultTimeoutController(
                new TimeoutTask() {

                    @Override
                    public boolean canSchedule() {
                        return false;
                    }

                    @Override
                    public void run() {
                        throw new Error("Should not reach here");
                    }
                },
                CommonPools.workerGroup().next());

        assertThat(timeoutController.scheduleTimeout(1000)).isFalse();
        assertThat(timeoutController.extendTimeout(2000)).isFalse();
        assertThat(timeoutController.resetTimeout(3000)).isFalse();
        assertThat(timeoutController.timeoutNow()).isFalse();
        assertThat(timeoutController.cancelTimeout()).isFalse();
    }

    private static class StatusCheckedTaskTimeoutController implements TimeoutController {

        private final DefaultTimeoutController delegate;

        StatusCheckedTaskTimeoutController(DefaultTimeoutController delegate) {
            // Assume the timeout task could be scheduled always
            assertThat(delegate.timeoutTask().canSchedule()).isTrue();
            this.delegate = delegate;
        }

        @Override
        public boolean scheduleTimeout(long timeoutNanos) {
            final State prevState = delegate.state();
            final boolean result = delegate.scheduleTimeout(timeoutNanos);
            if (result) {
                // Previous: DISABLED
                assertThat(prevState).isIn(State.INIT, State.INACTIVE);
                // Transition to: SCHEDULE
                assertThat(delegate.state()).isEqualTo(State.SCHEDULED);
            } else {
                // Previous: !DISABLED
                assertThat(prevState).isNotIn(State.INIT, State.INACTIVE);
                // Transition to: No changes
                assertThat(delegate.state()).isEqualTo(prevState);
            }
            return result;
        }

        @Override
        public boolean extendTimeout(long adjustmentNanos) {
            final State prevState = delegate.state();
            final boolean result = delegate.extendTimeout(adjustmentNanos);
            if (result) {
                // Previous: SCHEDULE
                assertThat(prevState).isEqualTo(State.SCHEDULED);
                // Transition to: SCHEDULE
                assertThat(delegate.state()).isEqualTo(State.SCHEDULED);
            } else {
                // Previous: !SCHEDULE
                assertThat(prevState).isNotEqualTo(State.SCHEDULED);
                // Transition to:
                if (prevState == State.INIT) {
                    assertThat(delegate.state()).isEqualTo(State.INACTIVE);
                } else {
                    assertThat(delegate.state()).isEqualTo(prevState);
                }
            }
            return result;
        }

        @Override
        public boolean resetTimeout(long newTimeoutNanos) {
            final State prevState = delegate.state();
            final boolean result = delegate.resetTimeout(newTimeoutNanos);
            if (result) {
                // Previous: SCHEDULED
                assertThat(prevState).isNotEqualTo(State.TIMED_OUT);
                // Transition to: SCHEDULE or DISABLED
                if (newTimeoutNanos > 0) {
                    assertThat(delegate.state()).isEqualTo(State.SCHEDULED);
                } else {
                    assertThat(delegate.state()).isEqualTo(State.INACTIVE);
                }
            } else {
                // Previous: TIMED_OUT
                assertThat(prevState).isEqualTo(State.TIMED_OUT);
                // Transition to: TIMED_OUT
                assertThat(delegate.state()).isEqualTo(State.TIMED_OUT);
            }
            return result;
        }

        @Override
        public boolean timeoutNow() {
            final State prevState = delegate.state();
            final boolean result = delegate.timeoutNow();
            if (result) {
                // Previous: !TIMED_OUT
                assertThat(prevState).isNotEqualTo(State.TIMED_OUT);
                // Transition to: TIMED_OUT
                assertThat(delegate.state()).isEqualTo(State.TIMED_OUT);
            } else {
                // Previous: TIMED_OUT
                assertThat(prevState).isEqualTo(State.TIMED_OUT);
                // Transition to: TIMED_OUT
                assertThat(delegate.state()).isEqualTo(State.TIMED_OUT);
            }
            return result;
        }

        @Override
        public boolean cancelTimeout() {
            final State prevState = delegate.state();
            final boolean canceled = delegate.cancelTimeout();
            if (canceled) {
                // Previous: SCHEDULED
                assertThat(prevState).isNotEqualTo(State.TIMED_OUT);
                // Transition to: TIMED_OUT
                assertThat(delegate.state()).isEqualTo(State.INACTIVE);
            } else {
                // Previous: !SCHEDULED
                assertThat(prevState).isNotEqualTo(State.SCHEDULED);
                // Transition to: No changes
                assertThat(delegate.state()).isEqualTo(prevState);
            }
            return canceled;
        }

        @Override
        public boolean isTimedOut() {
            return delegate.isTimedOut();
        }

        @Override
        public Long startTimeNanos() {
            return delegate.startTimeNanos();
        }

        long timeoutNanos() {
            return delegate.timeoutNanos();
        }

        @Nullable
        public ScheduledFuture<?> timeoutFuture() {
            return delegate.timeoutFuture();
        }

        public State state() {
            return delegate.state();
        }
    }
}
