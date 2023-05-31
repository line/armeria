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

import java.util.function.Supplier;

import com.linecorp.armeria.common.AttributesGetters;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RequestContextStorage;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.SafeCloseable;

import io.netty.util.AttributeKey;

/**
 * This class exposes extension methods for {@link RequestContext}
 * which are used internally by Armeria but aren't intended for public usage.
 */
public interface RequestContextExtension extends RequestContext {

    /**
     * Adds a hook which is invoked whenever this {@link NonWrappingRequestContext} is pushed to the
     * {@link RequestContextStorage}. The {@link AutoCloseable} returned by {@code contextHook} will be called
     * whenever this {@link RequestContext} is popped from the {@link RequestContextStorage}.
     * This method is useful when you need to propagate a custom context in this {@link RequestContext}'s scope.
     *
     * <p>Note that this operation is highly performance-sensitive operation, and thus
     * it's not a good idea to run a time-consuming task.
     */
    void hook(Supplier<? extends AutoCloseable> contextHook);

    /**
     * Returns the hook which is invoked whenever this {@link NonWrappingRequestContext} is pushed to the
     * {@link RequestContextStorage}. The {@link SafeCloseable} returned by the {@link Supplier} will be
     * called whenever this {@link RequestContext} is popped from the {@link RequestContextStorage}.
     */
    @Nullable
    Supplier<AutoCloseable> hook();

    /**
     * Returns the {@link AttributesGetters} which stores the pairs of an {@link AttributeKey} and an object
     * set via {@link #setAttr(AttributeKey, Object)}.
     */
    AttributesGetters attributes();

    /**
     * Returns the original {@link Request} that is specified when this {@link RequestContext} is created.
     */
    Request originalRequest();
}
