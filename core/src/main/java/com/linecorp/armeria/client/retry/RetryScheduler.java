/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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
package com.linecorp.armeria.client.retry;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientRequestContext;

import io.netty.util.concurrent.ScheduledFuture;

/**
 * Schedules retry tasks using the {@link ClientRequestContext}'s event loop.
 */
final class RetryScheduler {
    private final ClientRequestContext ctx;

    RetryScheduler(ClientRequestContext ctx) {
        this.ctx = ctx;
    }

    void scheduleNextRetry(long nextRetryTimeNanos, Runnable retryTask,
                           Consumer<? super Throwable> exceptionHandler) {
        try {
            final long nextRetryDelayMillis = TimeUnit.NANOSECONDS.toMillis(
                    nextRetryTimeNanos - System.nanoTime());
            if (nextRetryDelayMillis <= 0) {
                ctx.eventLoop().execute(retryTask);
            } else {
                @SuppressWarnings("unchecked")
                final ScheduledFuture<Void> scheduledFuture = (ScheduledFuture<Void>) ctx
                        .eventLoop().schedule(retryTask, nextRetryDelayMillis, TimeUnit.MILLISECONDS);
                scheduledFuture.addListener(future -> {
                    if (future.isCancelled()) {
                        // future is cancelled when the client factory is closed.
                        exceptionHandler.accept(new IllegalStateException(
                                ClientFactory.class.getSimpleName() + " has been closed."));
                    } else if (future.cause() != null) {
                        // Other unexpected exceptions.
                        exceptionHandler.accept(future.cause());
                    }
                });
            }
        } catch (Throwable t) {
            exceptionHandler.accept(t);
        }
    }

    // TODO: toString() method with info about current retry task.
}
