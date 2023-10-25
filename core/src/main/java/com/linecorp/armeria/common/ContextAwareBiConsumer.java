/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.common;

import java.util.function.BiConsumer;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A delegating {@link BiConsumer} that makes sure an underlying BiConsumer is
 * executed within the {@link RequestContext}.
 */
@UnstableApi
public interface ContextAwareBiConsumer<T, U> extends BiConsumer<T, U>, ContextHolder {

    /**
     * Returns a new {@link ContextAwareBiConsumer} that sets the specified {@link RequestContext}
     * before executing an underlying {@link BiConsumer}.
     */
    static <T, U> ContextAwareBiConsumer<T, U> of(RequestContext context, BiConsumer<T, U> action) {
        return new DefaultContextAwareBiConsumer<>(context, action);
    }

    /**
     * Returns the {@link RequestContext} that was specified when creating
     * this {@link ContextAwareBiConsumer}.
     */
    @Override
    RequestContext context();

    /**
     * Returns the {@link BiConsumer} that's executed without setting
     * the {@link RequestContext}.
     */
    BiConsumer<T, U> withoutContext();
}
