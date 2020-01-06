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

package com.linecorp.armeria.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.common.util.concurrent.Uninterruptibles;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.util.TimeoutController;
import com.linecorp.armeria.internal.DefaultTimeoutController.TimeoutTask;

class DefaultTimeoutControllerTest {

    static {
        // call workerGroup early to avoid initializing contexts while testing
        CommonPools.workerGroup();
    }

    DefaultTimeoutController timeoutController;
    volatile boolean isTimeout;

    @BeforeEach
    void setUp() {
        isTimeout = false;
        final TimeoutTask timeoutTask = new TimeoutTask() {
            @Override
            public boolean canSchedule() {
                return true;
            }

            @Override
            public void run() {
                isTimeout = true;
            }
        };
        timeoutController = new DefaultTimeoutController(timeoutTask, CommonPools.workerGroup().next());
    }

    @Test
    void shouldCallInitTimeout() {
        assertThatThrownBy(() -> timeoutController.extendTimeout(10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("initTimeout(timeoutMillis) is not called yet");
    }

    @Test
    void shouldHaveTimeoutTask() {
        final TimeoutController emptyTaskTimeoutController =
                new DefaultTimeoutController(CommonPools.workerGroup().next());
        assertThatThrownBy(() -> emptyTaskTimeoutController.extendTimeout(10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("setTimeoutTask(timeoutTask) is not called yet");
    }

    @Test
    void adjustTimeout() {
        final long initTimeoutMillis = 100;
        final long adjustmentMillis = 10;
        final long tolerance = 5;

        timeoutController.initTimeout(initTimeoutMillis);
        final long startTimeNanos = timeoutController.startTimeNanos();

        timeoutController.extendTimeout(adjustmentMillis);
        final long passedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTimeNanos);
        assertThat(timeoutController.timeoutMillis()).isBetween(
                initTimeoutMillis + adjustmentMillis - passedMillis - tolerance,
                initTimeoutMillis + adjustmentMillis - passedMillis + tolerance);

        final long adjustmentMillis2 = -20;
        timeoutController.extendTimeout(adjustmentMillis2);
        final long passedMillis2 = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTimeNanos);
        assertThat(timeoutController.timeoutMillis()).isBetween(
                initTimeoutMillis + adjustmentMillis + adjustmentMillis2 - passedMillis2 - tolerance,
                initTimeoutMillis + adjustmentMillis + adjustmentMillis2 - passedMillis2 + tolerance);
    }

    @Test
    void resetTimeout() {
        timeoutController.initTimeout(100);
        timeoutController.resetTimeout(10);
        assertThat(timeoutController.timeoutMillis()).isEqualTo(10);
    }

    @Test
    void resetTimeoutWithZeroInit() {
        timeoutController.initTimeout(0);
        timeoutController.resetTimeout(10);
        assertThat(timeoutController.timeoutMillis()).isEqualTo(10);
        assertThat((Object) timeoutController.timeoutFuture()).isNotNull();
    }

    @Test
    void resetTimout_multipleZero() {
        timeoutController.initTimeout(100);
        timeoutController.resetTimeout(0);
        timeoutController.resetTimeout(0);
    }

    @Test
    void resetTimout_multipleNonZero() {
        timeoutController.initTimeout(100);
        timeoutController.resetTimeout(0);
        timeoutController.resetTimeout(20);
    }

    @Test
    void cancelTimeout_beforeDeadline() {
        timeoutController.initTimeout(100);
        assertThat(timeoutController.cancelTimeout()).isTrue();
        assertThat(isTimeout).isFalse();
    }

    @Test
    void cancelTimeout_afterDeadline() {
        timeoutController.initTimeout(100);
        Uninterruptibles.sleepUninterruptibly(200, TimeUnit.MILLISECONDS);
        assertThat(timeoutController.cancelTimeout()).isFalse();
        assertThat(isTimeout).isTrue();
    }

    @Test
    void cancelTimeout_byResetTimeoutZero() {
        timeoutController.initTimeout(100);
        timeoutController.resetTimeout(0);
        assertThat(timeoutController.timeoutMillis()).isEqualTo(0);
        assertThat((Object) timeoutController.timeoutFuture()).isNull();
    }
}
