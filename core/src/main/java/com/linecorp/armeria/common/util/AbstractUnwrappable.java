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

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.annotation.Nullable;

/**
 * Skeletal {@link Unwrappable} implementation.
 *
 * @param <T> the type of the object being decorated
 */
public abstract class AbstractUnwrappable<T extends Unwrappable> implements Unwrappable {

    private final T delegate;

    /**
     * Creates a new decorator with the specified delegate.
     */
    protected AbstractUnwrappable(T delegate) {
        this.delegate = requireNonNull(delegate, "delegate");
    }

    @Override
    public final <U> U as(Class<U> type) {
        @Nullable final U result = Unwrappable.super.as(type);
        return result != null ? result : delegate.as(type);
    }

    @Override
    public T unwrap() {
        return delegate;
    }

    @Override
    public String toString() {
        final String simpleName = getClass().getSimpleName();
        final String name = simpleName.isEmpty() ? getClass().getName() : simpleName;
        return name + '(' + delegate + ')';
    }
}
