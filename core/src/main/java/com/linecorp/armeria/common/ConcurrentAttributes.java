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

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.netty.util.AttributeKey;

/**
 * An {@link Attributes} supporting concurrency of retrievals and updates.
 */
@UnstableApi
public interface ConcurrentAttributes extends AttributesGetters, AttributesSetters {

    /**
     * Returns a new empty {@link ConcurrentAttributes}.
     */
    static ConcurrentAttributes of() {
        return new DefaultConcurrentAttributes(null);
    }

    /**
     * Returns a new {@link ConcurrentAttributes} with the specified parent {@link AttributesGetters}.
     * The parent {@link AttributesGetters} can be accessed via {@link #attr(AttributeKey)} or {@link #attrs()}.
     *
     * <p>Note that any mutations in {@link ConcurrentAttributes} won't modify the attributes in the parent.
     */
    static ConcurrentAttributes fromParent(AttributesGetters parent) {
        requireNonNull(parent, "parent");
        return new DefaultConcurrentAttributes(parent);
    }

    @Override
    <T> ConcurrentAttributes set(AttributeKey<T> key, @Nullable T value);

    @Override
    default <T> ConcurrentAttributes removeAndThen(AttributeKey<T> key) {
        return (ConcurrentAttributes) AttributesSetters.super.removeAndThen(key);
    }
}
