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

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.google.common.math.LongMath;

import com.linecorp.armeria.common.util.TimeoutMode;

import io.netty.channel.EventLoop;

public final class TimeoutScheduler {

    private long timeoutMillis;
    @Nullable
    private Consumer<TimeoutController> pendingTimeoutTask;
    @Nullable
    private EventLoop eventLoop;
    @Nullable
    private TimeoutController timeoutController;

    public TimeoutScheduler(long timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    public void clearTimeout() {
        if (timeoutMillis == 0) {
            return;
        }

        final TimeoutController timeoutController = this.timeoutController;
        timeoutMillis = 0;
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

    public void setTimeoutMillis(TimeoutMode mode, long timeoutMillis) {
        switch (mode) {
            case SET_FROM_NOW:
                setTimeoutAfterMillis(timeoutMillis);
                break;
            case SET_FROM_START:
                setTimeoutMillis(timeoutMillis);
                break;
            case EXTEND:
                extendTimeoutMillis(timeoutMillis);
                break;
        }
    }

    private void setTimeoutMillis(long timeoutMillis) {
        checkArgument(timeoutMillis >= 0, "timeoutMillis: %s (expected: >= 0)", timeoutMillis);
        if (timeoutMillis == 0) {
            clearTimeout();
            return;
        }

        if (this.timeoutMillis == 0) {
            setTimeoutAfterMillis(timeoutMillis);
            return;
        }

        final long adjustmentMillis = LongMath.saturatedSubtract(timeoutMillis, this.timeoutMillis);
        extendTimeoutMillis(adjustmentMillis);
    }

    private void extendTimeoutMillis(long adjustmentMillis) {
        if (adjustmentMillis == 0 || timeoutMillis == 0) {
            return;
        }

        final long oldTimeoutMillis = timeoutMillis;
        timeoutMillis = LongMath.saturatedAdd(oldTimeoutMillis, adjustmentMillis);
        final TimeoutController timeoutController = this.timeoutController;
        if (timeoutController != null) {
            if (eventLoop.inEventLoop()) {
                timeoutController.extendTimeout(adjustmentMillis);
            } else {
                eventLoop.execute(() -> timeoutController.extendTimeout(adjustmentMillis));
            }
        } else {
            addPendingTimeoutTask(controller -> controller.extendTimeout(adjustmentMillis));
        }
    }

    private void setTimeoutAfterMillis(long timeoutMillis) {
        checkArgument(timeoutMillis > 0, "timeoutMillis: %s (expected: > 0)", timeoutMillis);

        long passedTimeMillis = 0;
        final TimeoutController timeoutController = this.timeoutController;
        if (timeoutController != null) {
            final Long startTimeNanos = timeoutController.startTimeNanos();
            if (startTimeNanos != null) {
                passedTimeMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTimeNanos);
            }
            if (eventLoop.inEventLoop()) {
                timeoutController.resetTimeout(timeoutMillis);
            } else {
                eventLoop.execute(() -> timeoutController.resetTimeout(timeoutMillis));
            }
        } else {
            final long startTimeNanos = System.nanoTime();
            addPendingTimeoutTask(controller -> {
                final long passedTimeMillis0 =
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTimeNanos);
                final long timeoutMillis0 = Math.max(1, timeoutMillis - passedTimeMillis0);
                controller.resetTimeout(timeoutMillis0);
            });
        }

        this.timeoutMillis = LongMath.saturatedAdd(passedTimeMillis, timeoutMillis);
    }

    @Deprecated
    public void setTimeoutAtMillis(long timeoutAtMillis) {
        checkArgument(timeoutAtMillis >= 0, "timeoutAtMillis: %s (expected: >= 0)", timeoutAtMillis);
        final long timeoutAfter = timeoutAtMillis - System.currentTimeMillis();

        if (timeoutAfter <= 0) {
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
        } else {
            setTimeoutAfterMillis(timeoutAfter);
        }
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

    public long timeoutMillis() {
        return timeoutMillis;
    }

    private void addPendingTimeoutTask(Consumer<TimeoutController> pendingTimeoutTask) {
        if (this.pendingTimeoutTask == null) {
            this.pendingTimeoutTask = pendingTimeoutTask;
        } else {
            this.pendingTimeoutTask = this.pendingTimeoutTask.andThen(pendingTimeoutTask);
        }
    }
}
