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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.google.common.math.LongMath;

import com.linecorp.armeria.common.util.TimeoutMode;

import io.netty.channel.EventLoop;

public final class TimeoutScheduler {

    private long timeoutNanos;
    @Nullable
    private Consumer<TimeoutController> pendingTimeoutTask;
    @Nullable
    private EventLoop eventLoop;
    @Nullable
    private TimeoutController timeoutController;

    public TimeoutScheduler(long timeoutNanos) {
        this.timeoutNanos = timeoutNanos;
    }

    public void clearTimeout() {
        if (timeoutNanos == 0) {
            return;
        }

        final TimeoutController timeoutController = this.timeoutController;
        timeoutNanos = 0;
        if (timeoutController != null) {
            if (eventLoop.inEventLoop()) {
                timeoutController.cancelTimeout();
            } else {
                eventLoop.execute(timeoutController::cancelTimeout);
            }
        } else {
            addPendingTimeoutTask(TimeoutController::cancelTimeout);
        }
    }

    public void setTimeoutNanos(TimeoutMode mode, long timeoutNanos) {
        switch (mode) {
            case SET_FROM_NOW:
                setTimeoutAfterNanos(timeoutNanos);
                break;
            case SET_FROM_START:
                setTimeoutNanos(timeoutNanos);
                break;
            case EXTEND:
                extendTimeoutNanos(timeoutNanos);
                break;
        }
    }

    private void setTimeoutNanos(long timeoutNanos) {
        checkArgument(timeoutNanos >= 0, "timeoutNanos: %s (expected: >= 0)", timeoutNanos);
        if (timeoutNanos == 0) {
            clearTimeout();
            return;
        }

        if (this.timeoutNanos == 0) {
            setTimeoutAfterNanos(timeoutNanos);
            return;
        }

        final long adjustmentNanos = LongMath.saturatedSubtract(timeoutNanos, this.timeoutNanos);
        extendTimeoutNanos(adjustmentNanos);
    }

    private void extendTimeoutNanos(long adjustmentNanos) {
        if (adjustmentNanos == 0 || timeoutNanos == 0) {
            return;
        }

        final long oldTimeoutNanos = timeoutNanos;
        timeoutNanos = LongMath.saturatedAdd(oldTimeoutNanos, adjustmentNanos);
        final TimeoutController timeoutController = this.timeoutController;
        if (timeoutController != null) {
            if (eventLoop.inEventLoop()) {
                timeoutController.extendTimeout(adjustmentNanos);
            } else {
                eventLoop.execute(() -> timeoutController.extendTimeout(adjustmentNanos));
            }
        } else {
            addPendingTimeoutTask(controller -> controller.extendTimeout(adjustmentNanos));
        }
    }

    private void setTimeoutAfterNanos(long timeoutNanos) {
        checkArgument(timeoutNanos > 0, "timeoutNanos: %s (expected: > 0)", timeoutNanos);

        long passedTimeNanos = 0;
        final TimeoutController timeoutController = this.timeoutController;
        if (timeoutController != null) {
            final Long startTimeNanos = timeoutController.startTimeNanos();
            if (startTimeNanos != null) {
                passedTimeNanos = System.nanoTime() - startTimeNanos;
            }
            if (eventLoop.inEventLoop()) {
                timeoutController.resetTimeout(timeoutNanos);
            } else {
                eventLoop.execute(() -> timeoutController.resetTimeout(timeoutNanos));
            }
        } else {
            final long startTimeNanos = System.nanoTime();
            addPendingTimeoutTask(controller -> {
                final long passedTimeNanos0 = System.nanoTime() - startTimeNanos;
                final long timeoutNanos0 = Math.max(1, timeoutNanos - passedTimeNanos0);
                controller.resetTimeout(timeoutNanos0);
            });
        }

        this.timeoutNanos = LongMath.saturatedAdd(passedTimeNanos, timeoutNanos);
    }

    public void timeoutNow() {
        final TimeoutController timeoutController = this.timeoutController;
        if (timeoutController != null) {
            if (eventLoop.inEventLoop()) {
                timeoutController.timeoutNow();
            } else {
                eventLoop.execute(timeoutController::timeoutNow);
            }
        } else {
            addPendingTimeoutTask(TimeoutController::timeoutNow);
        }
    }

    public boolean isTimedOut() {
        if (timeoutController == null) {
            return false;
        }
        return timeoutController.isTimedOut();
    }

    public void setTimeoutController(TimeoutController timeoutController, EventLoop eventLoop) {
        requireNonNull(timeoutController, "timeoutController");
        requireNonNull(eventLoop, "eventLoop");
        checkState(this.timeoutController == null, "timeoutController is set already.");
        this.timeoutController = timeoutController;
        this.eventLoop = eventLoop;

        final Consumer<TimeoutController> pendingTimeoutTask = this.pendingTimeoutTask;
        if (pendingTimeoutTask != null) {
            if (eventLoop.inEventLoop()) {
                pendingTimeoutTask.accept(timeoutController);
            } else {
                eventLoop.execute(() -> pendingTimeoutTask.accept(timeoutController));
            }
        }
    }

    public long timeoutNanos() {
        return timeoutNanos;
    }

    private void addPendingTimeoutTask(Consumer<TimeoutController> pendingTimeoutTask) {
        if (this.pendingTimeoutTask == null) {
            this.pendingTimeoutTask = pendingTimeoutTask;
        } else {
            this.pendingTimeoutTask = this.pendingTimeoutTask.andThen(pendingTimeoutTask);
        }
    }
}
