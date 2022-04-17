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

package com.linecorp.armeria.internal.common;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.annotation.Nullable;

/**
 * A utility class for shutdown
 */
public final class ShutdownUtil {

    private static final Logger logger = LoggerFactory.getLogger(ShutdownUtil.class);

    /**
     *　Provides a {@link Runnable} to pass to the JVM shutdown hook
     */
    public static Runnable newClosingTask(@Nullable Runnable whenClosing,
                                          Supplier<CompletableFuture<?>> closeAction,
                                          CompletableFuture<Void> closeFuture, String name) {
        return () -> {
            if (whenClosing != null) {
                try {
                    whenClosing.run();
                } catch (Exception e) {
                    logger.warn("whenClosing failed", e);
                }
            }
            closeAction.get().handle((unused, cause) -> {
                if (cause != null) {
                    logger.warn("Unexpected exception while closing a {}.", name, cause);
                    closeFuture.completeExceptionally(cause);
                } else {
                    logger.debug("{} has been closed.", name);
                    closeFuture.complete(null);
                }
                return null;
            }).join();
        };
    }

    private ShutdownUtil() {}
}
