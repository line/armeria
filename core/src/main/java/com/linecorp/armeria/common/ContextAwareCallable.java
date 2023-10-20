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

import java.util.concurrent.Callable;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A delegating {@link Callable} that makes sure an underlying Callable is
 * executed within the {@link RequestContext}.
 */
@UnstableApi
public interface ContextAwareCallable<T> extends Callable<T>, ContextHolder {

    /**
     * Returns a new {@link ContextAwareCallable} that sets the specified {@link RequestContext}
     * before executing an underlying {@link Callable}.
     */
    static <T> ContextAwareCallable<T> of(RequestContext context, Callable<T> callable) {
        return new DefaultContextAwareCallable<T>(context, callable);
    }

    /**
     * Returns the {@link RequestContext} that was specified when creating
     * this {@link ContextAwareCallable}.
     */
    @Override
    RequestContext context();

    /**
     * Returns the {@link Callable} that's executed without setting
     * the {@link RequestContext}.
     */
    Callable<T> withoutContext();
}
