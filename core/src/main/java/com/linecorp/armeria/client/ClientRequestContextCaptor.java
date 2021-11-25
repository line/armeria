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
package com.linecorp.armeria.client;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Supplier;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.SafeCloseable;

/**
 * Captures the {@link ClientRequestContext}s created by the current thread.
 *
 * @see Clients#newContextCaptor()
 */
public interface ClientRequestContextCaptor extends SafeCloseable, Supplier<ClientRequestContext> {

    /**
     * Returns the {@link ClientRequestContext} captured first.
     *
     * @throws NoSuchElementException if no {@link ClientRequestContext} was captured so far.
     */
    @Override
    ClientRequestContext get();

    /**
     * Returns the {@link ClientRequestContext} captured first, or {@code null} if unavailable.
     */
    @Nullable
    ClientRequestContext getOrNull();

    /**
     * Returns all {@link ClientRequestContext}s captured so far. An empty list is returned
     * if no {@link ClientRequestContext} was captured so far.
     */
    List<ClientRequestContext> getAll();

    /**
     * Returns the number of {@link ClientRequestContext} captured so far.
     */
    int size();

    /**
     * Returns whether a {@link ClientRequestContext} was captured so far.
     */
    default boolean isEmpty() {
        return size() == 0;
    }
}
