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

package com.linecorp.armeria.common;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.netty.util.AttributeKey;

/**
 * Sets entries for building an {@link Attributes}.
 */
@UnstableApi
public interface AttributesSetters extends AttributesGetters {

    /**
     * Sets the specified value with the given {@link AttributeKey}.
     * The old value associated with the {@link AttributeKey} is replaced by the specified value.
     * If a {@code null} value is specified, the old value is removed in the {@link Attributes}.
     */
    @Nullable <T> T getAndSet(AttributeKey<T> key, @Nullable T value);

    /**
     * Sets the specified value with the given {@link AttributeKey}.
     * The old value associated with the {@link AttributeKey} is replaced by the specified value.
     */
    <T> AttributesSetters set(AttributeKey<T> key, T value);

    /**
     * Removes the value associated with the specified {@link AttributeKey}.
     * Note that this method won't remove the value in {@link Attributes#parent()}.
     */
    <T> AttributesSetters remove(AttributeKey<T> key);
}
