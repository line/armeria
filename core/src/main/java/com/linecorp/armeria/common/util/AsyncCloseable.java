/*
 * Copyright 2019 LINE Corporation
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

import java.util.concurrent.CompletableFuture;

/**
 * An object that may hold resources until it is closed. Unlike {@link AutoCloseable}, the {@link #closeAsync()}
 * method releases the resources asynchronously, returning the {@link CompletableFuture} which is completed
 * after the resources are released.
 */
public interface AsyncCloseable extends AutoCloseable {
    /**
     * Returns the {@link CompletableFuture} which is completed after the resources are released. Note that
     * you must use {@link #close()} or {@link #closeAsync()} to release the resources actually. This method
     * merely returns the {@link CompletableFuture}.
     */
    CompletableFuture<?> closeFuture();

    /**
     * Releases any underlying resources held by this object asynchronously.
     *
     * @return the {@link CompletableFuture} which is completed after the resources are released
     */
    CompletableFuture<?> closeAsync();

    /**
     * Releases any underlying resources held by this object synchronously.
     */
    @Override
    void close();
}
