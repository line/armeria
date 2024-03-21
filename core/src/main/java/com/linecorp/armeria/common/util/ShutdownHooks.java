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

package com.linecorp.armeria.common.util;

import static java.util.Objects.requireNonNull;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.errorprone.annotations.concurrent.GuardedBy;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.internal.common.util.ReentrantShortLock;

/**
 * A utility class for adding a task with an {@link AutoCloseable} on shutdown.
 */
@UnstableApi
public final class ShutdownHooks {

    private static final Logger logger = LoggerFactory.getLogger(ShutdownHooks.class);

    @GuardedBy("reentrantLock")
    private static final Map<AutoCloseable, Queue<Runnable>> autoCloseableOnShutdownTasks =
            new LinkedHashMap<>();

    private static final ReentrantLock reentrantLock = new ReentrantShortLock();

    private static final ThreadFactory THREAD_FACTORY = ThreadFactories
            .builder("armeria-shutdown-hook")
            .build();

    private static boolean addedShutdownHook;

    /**
     *　Adds an {@link AutoCloseable} to the JVM shutdown hook.
     */
    public static CompletableFuture<Void> addClosingTask(AutoCloseable autoCloseable) {
        return addClosingTask(autoCloseable, null, autoCloseable.getClass().getSimpleName());
    }

    /**
     *　Adds an {@link AutoCloseable} and a {@link Runnable} to the JVM shutdown hook.
     */
    public static CompletableFuture<Void> addClosingTask(
            AutoCloseable autoCloseable, Runnable whenClosing) {
        requireNonNull(whenClosing, "whenClosing");
        return addClosingTask(autoCloseable, whenClosing, autoCloseable.getClass().getSimpleName());
    }

    /**
     *　Adds an {@link AutoCloseable} and a {@link Runnable} to the JVM shutdown hook.
     */
    private static CompletableFuture<Void> addClosingTask(
            AutoCloseable autoCloseable, @Nullable Runnable whenClosing, String name) {
        final CompletableFuture<Void> closeFuture = new CompletableFuture<>();
        final Runnable task = () -> {
            if (whenClosing != null) {
                try {
                    whenClosing.run();
                } catch (Exception e) {
                    logger.warn("Unexpected exception while running a shutdown callback:", e);
                }
            }
            try {
                autoCloseable.close();
                logger.debug("{} has been closed.", name);
                closeFuture.complete(null);
            } catch (Throwable cause) {
                logger.warn("Unexpected exception while closing a {}.", name, cause);
                closeFuture.completeExceptionally(cause);
            }
        };

        reentrantLock.lock();
        try {
            final Queue<Runnable> onShutdownTasks =
                    autoCloseableOnShutdownTasks.computeIfAbsent(autoCloseable, key -> new ArrayDeque<>());
            onShutdownTasks.add(task);
            if (!addedShutdownHook) {
                Runtime.getRuntime().addShutdownHook(THREAD_FACTORY.newThread(() -> {
                    reentrantLock.lock();
                    try {
                        autoCloseableOnShutdownTasks.forEach((factory, queue) -> {
                            for (;;) {
                                final Runnable onShutdown = queue.poll();
                                if (onShutdown == null) {
                                    break;
                                } else {
                                    onShutdown.run();
                                }
                            }
                        });
                    } finally {
                        reentrantLock.unlock();
                    }
                }));
                addedShutdownHook = true;
            }
        } finally {
            reentrantLock.unlock();
        }
        return closeFuture;
    }

    private ShutdownHooks() {}
}
