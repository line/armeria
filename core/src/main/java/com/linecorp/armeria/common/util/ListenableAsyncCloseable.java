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
package com.linecorp.armeria.common.util;

import java.util.concurrent.CompletableFuture;

/**
 * A variant of {@link AsyncCloseable} which allows a user to check whether the object is closed or to get
 * notified when closed.
 */
public interface ListenableAsyncCloseable extends AsyncCloseable {
    /**
     * Returns whether {@link #close()} or {@link #closeAsync()} has been called.
     *
     * @see #isClosed()
     */
    boolean isClosing();

    /**
     * Returns whether {@link #close()} or {@link #closeAsync()} operation has been completed.
     *
     * @see #isClosing()
     */
    boolean isClosed();

    /**
     * Returns the {@link CompletableFuture} which is completed after the {@link #close()} or
     * {@link #closeAsync()} operation is completed.
     */
    CompletableFuture<?> whenClosed();
}
