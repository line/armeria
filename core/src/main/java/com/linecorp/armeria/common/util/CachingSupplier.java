/*
 * Copyright 2021 LINE Corporation
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

import java.util.function.Supplier;

import com.linecorp.armeria.client.limit.ConcurrencyLimit;
import com.linecorp.armeria.common.annotation.Nullable;

/**
 * Supplies the cached value that is useful below cases.
 * <p>
 * - Supplies dynamically a maximum number of concurrent active requests for {@link ConcurrencyLimit}.
 */
public class CachingSupplier<T> implements Supplier<T> {
    @Nullable
    private volatile T value;

    /**
     * Creates a new instance with the specified {@code initialValue}.
     */
    public static <T> CachingSupplier<T> of(@Nullable T initialValue) {
        return new CachingSupplier<>(initialValue);
    }

    protected CachingSupplier(@Nullable T initialValue) {
        value = initialValue;
    }

    /**
     * Returns the cached value
     */
    @Override
    @Nullable
    public final T get() {
        return value;
    }

    /**
     * Caches a value.
     */
    public final void set(@Nullable T value) {
        this.value = value;
    }
}
