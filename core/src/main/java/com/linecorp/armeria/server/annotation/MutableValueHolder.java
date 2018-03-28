/*
 * Copyright 2018 LINE Corporation
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
package com.linecorp.armeria.server.annotation;

import java.util.function.Supplier;

import javax.annotation.Nullable;

/**
 * Mutable object value holder.
 *
 * @param <T> Type of value.
 */
final class MutableValueHolder<T> {
    @Nullable
    private T value;

    private MutableValueHolder(@Nullable final T value) {
        this.value = value;
    }

    public static <T> MutableValueHolder<T> of(@Nullable final T value) {
        return new MutableValueHolder<>(value);
    }

    public static <T> MutableValueHolder<T> ofEmpty() {
        return of(null);
    }

    @Nullable
    public T getValue() {
        return value;
    }

    public void setValue(@Nullable final T value) {
        this.value = value;
    }

    /**
     * If the value is not {@code null}, return it.
     * If the value is {@code null}, attempts to compute its value using the given supplier function
     * and set it into this Holder.
     *
     * @param valueSupplier the function to compute a value
     *
     * @return the current (existing or computed) value, or {@code null} if the computed value is {@code null}
     */
    @Nullable
    public T computeIfAbsent(final Supplier<T> valueSupplier) {
        if (value == null) {
            value = valueSupplier.get();
        }
        return value;
    }
}
