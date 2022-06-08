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

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A utility class for adding a task with closing a {@link AutoCloseable} on shutdown.
 */
@UnstableApi
public final class ShutdownHooks {

    private static final Logger logger = LoggerFactory.getLogger(ShutdownHooks.class);

    private static final Map<AutoCloseable, Queue<Runnable>> asyncCloseableOnShutdownTasks =
            new LinkedHashMap<>();

    private static final ThreadFactory THREAD_FACTORY = ThreadFactories
            .builder("armeria-shutdown-hook")
            .build();

    private static boolean addedShutdownHook;

    /**
     *　Adds a {@link Runnable} and a {@link AutoCloseable} to the JVM shutdown hook.
     */
    public static CompletableFuture<Void> addClosingTask(
            @Nullable Runnable whenClosing, AutoCloseable asyncCloseable, String name) {
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
                asyncCloseable.close();
                logger.debug("{} has been closed.", name);
                closeFuture.complete(null);
            } catch (Throwable cause) {
                logger.warn("Unexpected exception while closing a {}.", name, cause);
                closeFuture.completeExceptionally(cause);
            }
        };
        synchronized (asyncCloseableOnShutdownTasks) {
            final Queue<Runnable> onShutdownTasks;
            if (asyncCloseableOnShutdownTasks.containsKey(asyncCloseable)) {
                onShutdownTasks = asyncCloseableOnShutdownTasks.get(asyncCloseable);
            } else {
                onShutdownTasks = new ArrayDeque<>();
            }
            onShutdownTasks.add(task);
            asyncCloseableOnShutdownTasks.put(asyncCloseable, onShutdownTasks);
            if (!addedShutdownHook) {
                Runtime.getRuntime().addShutdownHook(THREAD_FACTORY.newThread(() -> {
                    asyncCloseableOnShutdownTasks.forEach((factory, queue) -> {
                        for (;;) {
                            final Runnable onShutdown = queue.poll();
                            if (onShutdown == null) {
                                break;
                            } else {
                                onShutdown.run();
                            }
                        }
                    });
                }));
                addedShutdownHook = true;
            }
        }
        return closeFuture;
    }

    private ShutdownHooks() {}
}
