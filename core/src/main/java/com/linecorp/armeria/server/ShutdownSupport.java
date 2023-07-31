/*
 * Copyright 2022 LINE Corporation
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
package com.linecorp.armeria.server;

import static com.linecorp.armeria.server.Server.logger;
import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.server.logging.AccessLogWriter;

interface ShutdownSupport {

    static ShutdownSupport of(AccessLogWriter accessLogWriter) {
        requireNonNull(accessLogWriter, "accessLogWriter");
        return () -> accessLogWriter.shutdown().exceptionally(cause -> {
            logger.warn("Failed to shutdown the {}:", accessLogWriter, cause);
            return null;
        });
    }

    static ShutdownSupport of(ExecutorService executor) {
        requireNonNull(executor, "executor");
        return of(executor, e -> {
            e.shutdown();
            try {
                while (!e.isTerminated()) {
                    e.awaitTermination(1, TimeUnit.HOURS);
                }
            } catch (InterruptedException cause) {
                logger.warn("During the termination wait, an interrupt occurs, attempting to forcefully " +
                            "terminate the {}:", e, cause);
                e.shutdownNow();
                Thread.currentThread().interrupt();
            }
        });
    }

    static ShutdownSupport of(ExecutorService executor, long terminationTimeout, TimeUnit timeUnit) {
        requireNonNull(executor, "executor");
        requireNonNull(timeUnit, "timeUnit");
        return of(executor, e -> {
            e.shutdown();
            try {
                if (!e.awaitTermination(terminationTimeout, timeUnit)) {
                    logger.warn("As the termination does not complete within the specified timeout, an " +
                                "attempt is made to forcefully terminate the {}:", e);
                    e.shutdownNow();
                    if (!e.awaitTermination(60, TimeUnit.SECONDS)) {
                        logger.warn("The forced termination of the {} could not be completed within 60 " +
                                    "seconds.", e);
                    }
                }
            } catch (InterruptedException cause) {
                logger.warn("During the termination wait, an interrupt occurs, attempting to forcefully " +
                            "terminate the {}:", executor, cause);
                e.shutdownNow();
                Thread.currentThread().interrupt();
            }
        });
    }

    static ShutdownSupport of(ExecutorService executor, Consumer<ExecutorService> shutdownStrategy) {
        requireNonNull(executor, "executor");
        return () -> {
            final ExecutorService e;
            if (executor instanceof UnstoppableScheduledExecutorService) {
                e = ((UnstoppableScheduledExecutorService) executor).getExecutorService();
            } else {
                e = executor;
            }
            shutdownStrategy.accept(e);
            return UnmodifiableFuture.completedFuture(null);
        };
    }

    static ShutdownSupport of(AutoCloseable autoCloseable) {
        requireNonNull(autoCloseable, "autoCloseable");
        return () -> {
            try {
                autoCloseable.close();
            } catch (Exception e) {
                logger.warn("Unexpected exception while closing: {}", autoCloseable, e);
            }
            return UnmodifiableFuture.completedFuture(null);
        };
    }

    CompletableFuture<Void> shutdown();
}
