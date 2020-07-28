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
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.util.TimeoutMode;
import com.linecorp.armeria.internal.common.TimeoutScheduler.State;
import com.linecorp.armeria.internal.common.TimeoutScheduler.TimeoutTask;

import io.netty.util.concurrent.EventExecutor;

class DefaultTimeoutControllerTest {

    private static final Logger logger = LoggerFactory.getLogger(DefaultTimeoutControllerTest.class);

    private static final EventExecutor eventExecutor = CommonPools.workerGroup().next();

    private static final TimeoutTask noopTimeoutTask = new TimeoutTask() {
        @Override
        public boolean canSchedule() {
            return true;
        }

        @Override
        public void run() {}
    };

    private static void executeInEventLoop(long initTimeoutNanos, Consumer<TimeoutScheduler> task) {
        final AtomicBoolean completed = new AtomicBoolean();
        eventExecutor.execute(() -> {
            try {
                final TimeoutScheduler timeoutScheduler = new TimeoutScheduler(0);
                timeoutScheduler.init(eventExecutor, noopTimeoutTask, initTimeoutNanos);
                task.accept(timeoutScheduler);
                completed.set(true);
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }
        });
        await().untilTrue(completed);
    }

    @Test
    void adjustTimeout() {
        final long initTimeoutNanos = Duration.ofMillis(1000).toNanos();
        final long adjustmentNanos = Duration.ofMillis(200).toNanos();
        final long tolerance = Duration.ofMillis(100).toNanos();

        executeInEventLoop(initTimeoutNanos, timeoutScheduler -> {
            final long startTimeNanos = timeoutScheduler.startTimeNanos();

            timeoutScheduler.setTimeoutNanos(TimeoutMode.EXTEND, adjustmentNanos);
            final long passedNanos = System.nanoTime() - startTimeNanos;
            assertThat(timeoutScheduler.timeoutNanos()).isBetween(
                    initTimeoutNanos + adjustmentNanos - passedNanos - tolerance,
                    initTimeoutNanos + adjustmentNanos - passedNanos + tolerance);

            final long adjustmentNanos2 = Duration.ofMillis(-200).toNanos();
            timeoutScheduler.setTimeoutNanos(TimeoutMode.EXTEND, adjustmentNanos2);
            final long passedMillis2 = System.nanoTime() - startTimeNanos;
            assertThat(timeoutScheduler.timeoutNanos()).isBetween(
                    initTimeoutNanos + adjustmentNanos + adjustmentNanos2 - passedMillis2 - tolerance,
                    initTimeoutNanos + adjustmentNanos + adjustmentNanos2 - passedMillis2 + tolerance);
        });
    }

    @Test
    void resetTimeout() {
        executeInEventLoop(0, timeoutScheduler -> {
            timeoutScheduler.setTimeoutNanos(TimeoutMode.SET_FROM_NOW, Duration.ofMillis(1000).toNanos());
            timeoutScheduler.setTimeoutNanos(TimeoutMode.SET_FROM_NOW, Duration.ofMillis(500).toNanos());
            assertTimeout(timeoutScheduler.timeoutNanos(), Duration.ofMillis(500).toNanos());
        });
    }

    @Test
    void resetTimeout_zero() {
        executeInEventLoop(0, timeoutScheduler -> {
            timeoutScheduler.setTimeoutNanos(TimeoutMode.SET_FROM_NOW, Duration.ofMillis(1000).toNanos());
            assertThatThrownBy(() -> {
                timeoutScheduler.setTimeoutNanos(TimeoutMode.SET_FROM_NOW, 0);
            }).isInstanceOf(IllegalArgumentException.class)
              .hasMessageContaining("timeoutNanos:");
        });
    }

    void assertTimeout(long actualNanos, long expectedNanos) {
        assertThat(actualNanos)
                .isCloseTo(expectedNanos, Offset.offset(Duration.ofMillis(200).toNanos()));
    }

    @Test
    void resetTimout_multipleNonZero() {
        executeInEventLoop(0, timeoutScheduler -> {
            timeoutScheduler.setTimeoutNanos(TimeoutMode.SET_FROM_NOW, Duration.ofMillis(1000).toNanos());
            timeoutScheduler.setTimeoutNanos(TimeoutMode.SET_FROM_NOW, Duration.ofMillis(500).toNanos());
        });
    }

    @Test
    void cancelTimeout_beforeDeadline() {
        executeInEventLoop(0, timeoutScheduler -> {
            timeoutScheduler.setTimeoutNanos(TimeoutMode.SET_FROM_NOW, Duration.ofMillis(1000).toNanos());
            timeoutScheduler.clearTimeout();
            assertThat(timeoutScheduler.isTimedOut()).isFalse();
        });
    }

    @Test
    void cancelTimeout_afterDeadline() {
        executeInEventLoop(0, timeoutScheduler -> {
            timeoutScheduler.timeoutNow();
            timeoutScheduler.clearTimeout();
            assertThat(timeoutScheduler.isTimedOut()).isTrue();
        });
    }

    @Test
    void cancelTimeout_byResetTimeoutZero() {
        executeInEventLoop(0, timeoutScheduler -> {
            timeoutScheduler.setTimeoutNanos(TimeoutMode.SET_FROM_NOW, Duration.ofMillis(1000).toNanos());
            timeoutScheduler.clearTimeout();
            assertThat((Object) timeoutScheduler.timeoutFuture()).isNull();
        });
    }

    @Test
    void scheduleTimeoutWhenTimedOut() {
        executeInEventLoop(0, timeoutScheduler -> {
            timeoutScheduler.timeoutNow();
            assertThat(timeoutScheduler.isTimedOut()).isTrue();
            timeoutScheduler.setTimeoutNanos(TimeoutMode.SET_FROM_NOW, Duration.ofMillis(1000).toNanos());
            assertThat(timeoutScheduler.isTimedOut()).isTrue();
        });
    }

    @Test
    void extendTimeoutWhenScheduled() {
        executeInEventLoop(0, timeoutScheduler -> {
            final long timeoutNanos = Duration.ofMillis(1000).toNanos();
            timeoutScheduler.setTimeoutNanos(TimeoutMode.SET_FROM_NOW, timeoutNanos);
            final long currentTimeoutNanos = timeoutScheduler.timeoutNanos();
            assertTimeout(currentTimeoutNanos, timeoutNanos);
            timeoutScheduler.setTimeoutNanos(TimeoutMode.EXTEND, timeoutNanos);
            assertTimeout(timeoutScheduler.timeoutNanos(), currentTimeoutNanos + timeoutNanos);
        });
    }

    @Test
    void extendTimeoutWhenTimedOut() {
        executeInEventLoop(0, timeoutScheduler -> {
            timeoutScheduler.timeoutNow();
            assertThat(timeoutScheduler.isTimedOut()).isTrue();
            timeoutScheduler.setTimeoutNanos(TimeoutMode.EXTEND, Duration.ofMillis(1000).toNanos());
            assertThat(timeoutScheduler.isTimedOut()).isTrue();
        });
    }

    @Test
    void cancelTimeoutWhenScheduled() {
        executeInEventLoop(0, timeoutScheduler -> {
            timeoutScheduler.setTimeoutNanos(TimeoutMode.SET_FROM_NOW, Duration.ofMillis(1000).toNanos());
            timeoutScheduler.clearTimeout();
        });
    }

    @Test
    void cancelTimeoutWhenTimedOut() {
        executeInEventLoop(0, timeoutScheduler -> {
            timeoutScheduler.timeoutNow();
            timeoutScheduler.clearTimeout();
            assertThat(timeoutScheduler.isTimedOut()).isTrue();
        });
    }

    @Test
    void timeoutNowWhenTimedOut() {
        executeInEventLoop(0, timeoutScheduler -> {
            timeoutScheduler.timeoutNow();
            assertThat(timeoutScheduler.isTimedOut()).isTrue();
            timeoutScheduler.timeoutNow();
            assertThat(timeoutScheduler.isTimedOut()).isTrue();
        });
    }

    @Test
    void whenTimingOut() {
        final AtomicReference<CompletableFuture<Void>> timeoutFutureRef = new AtomicReference<>();
        final AtomicReference<TimeoutScheduler> timeoutSchedulerRef = new AtomicReference<>();
        final AtomicBoolean completed = new AtomicBoolean();
        final AtomicBoolean passed = new AtomicBoolean();
        eventExecutor.execute(() -> {
            final TimeoutScheduler timeoutScheduler = new TimeoutScheduler(0);
            final TimeoutTask timeoutTask = new TimeoutTask() {
                @Override
                public boolean canSchedule() {
                    return true;
                }

                @Override
                public void run() {
                    assertThat(timeoutScheduler.whenTimingOut()).isDone();
                    assertThat(timeoutScheduler.isTimedOut()).isTrue();
                    assertThat(timeoutScheduler.whenTimedOut()).isNotDone();
                    passed.set(true);
                }
            };
            timeoutScheduler.init(eventExecutor, timeoutTask, 0);

            assertThat(timeoutScheduler.isTimedOut()).isFalse();

            timeoutScheduler.setTimeoutNanos(TimeoutMode.SET_FROM_NOW, Duration.ofMillis(1000).toNanos());
            assertThat(timeoutScheduler.state()).isEqualTo(State.SCHEDULED);

            timeoutSchedulerRef.set(timeoutScheduler);
            timeoutFutureRef.set(timeoutScheduler.whenTimedOut());
            completed.set(true);
        });

        await().untilTrue(passed);
        await().untilTrue(completed);
        timeoutFutureRef.get().join();
        assertThat(timeoutSchedulerRef.get().state()).isEqualTo(State.TIMED_OUT);
    }

    @Test
    void whenTimedOut() {
        final AtomicReference<CompletableFuture<Void>> timeoutFutureRef = new AtomicReference<>();
        executeInEventLoop(0, timeoutScheduler -> {
            final CompletableFuture<Void> timeoutFuture = timeoutScheduler.whenTimedOut();
            assertThat(timeoutFuture).isNotDone();
            timeoutScheduler.setTimeoutNanos(TimeoutMode.SET_FROM_NOW, Duration.ofMillis(1000).toNanos());
            timeoutFutureRef.set(timeoutFuture);
        });
        timeoutFutureRef.get().join();
    }

    @Test
    void pendingTimeout() {
        final TimeoutScheduler timeoutScheduler = new TimeoutScheduler(1000);
        timeoutScheduler.setTimeoutNanos(TimeoutMode.EXTEND, 1000);
        assertThat(timeoutScheduler.timeoutNanos()).isEqualTo(2000);
        timeoutScheduler.setTimeoutNanos(TimeoutMode.SET_FROM_NOW, 1000);
        assertThat(timeoutScheduler.timeoutNanos()).isEqualTo(1000);
        timeoutScheduler.setTimeoutNanos(TimeoutMode.SET_FROM_START, 3000);
        assertThat(timeoutScheduler.timeoutNanos()).isEqualTo(3000);
    }
}
