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

import java.util.function.BiFunction;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A delegating {@link BiFunction} that makes sure an underlying BiFunction is
 * executed within the {@link RequestContext}.
 */
@UnstableApi
public interface ContextAwareBiFunction<T, U, R> extends BiFunction<T, U, R>, ContextHolder {

    /**
     * Returns a new {@link ContextAwareBiFunction} that sets the specified {@link RequestContext}
     * before executing an underlying {@link BiFunction}.
     */
    static <T, U, R> ContextAwareBiFunction<T, U, R> of(RequestContext context, BiFunction<T, U, R> function) {
        return new DefaultContextAwareBiFunction<>(context, function);
    }

    /**
     * Returns the {@link RequestContext} that was specified when creating
     * this {@link ContextAwareBiFunction}.
     */
    @Override
    RequestContext context();

    /**
     * Returns the {@link BiFunction} that's executed without setting
     * the {@link RequestContext}.
     */
    BiFunction<T, U, R> withoutContext();
}
