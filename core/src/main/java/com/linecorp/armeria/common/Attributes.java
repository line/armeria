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

import java.util.function.Consumer;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.netty.util.AttributeKey;

/**
 * Holds attributes which can be accessed via {@link AttributeKey}.
 */
@UnstableApi
public interface Attributes extends AttributesGetters {

    /**
     * Returns an empty {@link Attributes}.
     */
    static Attributes of() {
        return ImmutableAttributes.EMPTY;
    }

    /**
     * Returns a new {@link Attributes} with the specified {@link AttributeKey} and {@code value}.
     */
    static <T> Attributes of(AttributeKey<T> key, T value) {
        return builder().set(key, value).build();
    }

    /**
     * Returns a new {@link Attributes} with the specified pairs of an {@link AttributeKey} and a {@code value}.
     */
    static <T, U> Attributes of(AttributeKey<T> k1, T v1, AttributeKey<U> k2, U v2) {
        return builder().set(k1, v1)
                        .set(k2, v2)
                        .build();
    }

    /**
     * Returns a new {@link Attributes} with the specified pairs of an {@link AttributeKey} and a {@code value}.
     */
    static <T, U, V> Attributes of(AttributeKey<T> k1, T v1,
                                   AttributeKey<U> k2, U v2,
                                   AttributeKey<V> k3, V v3) {
        return builder().set(k1, v1)
                        .set(k2, v2)
                        .set(k3, v3)
                        .build();
    }

    /**
     * Returns a new {@link Attributes} with the specified pairs of an {@link AttributeKey} and a {@code value}.
     */
    static <T, U, V, W> Attributes of(AttributeKey<T> k1, T v1,
                                      AttributeKey<U> k2, U v2,
                                      AttributeKey<V> k3, V v3,
                                      AttributeKey<W> k4, W v4) {
        return builder().set(k1, v1)
                        .set(k2, v2)
                        .set(k3, v3)
                        .set(k4, v4)
                        .build();
    }

    /**
     * Returns a new {@link Attributes} with the specified parent {@link Attributes}.
     * The parent {@link Attributes} can access via {@link #attr(AttributeKey)} or {@link #attrs()}.
     */
    static Attributes of(@Nullable AttributesGetters parent) {
        return builder(parent).build();
    }

    static AttributesSetters newConcurrentAttributes() {
        return newConcurrentAttributes(null);
    }


    static AttributesSetters newConcurrentAttributes(@Nullable AttributesGetters parent) {
        new DefaultConcurrentAttributes(parent, null);
    }

    /**
     * Returns a new empty {@link AttributesBuilder}.
     */
    static AttributesBuilder builder() {
        return builder(null);
    }

    /**
     * Returns a new empty {@link AttributesBuilder}.
     * The parent {@link Attributes} can access via {@link #attr(AttributeKey)} or {@link #attrs()}.
     * Note that any mutations in {@link AttributesBuilder} won't modify the attributes in the parent.
     */
    static AttributesBuilder builder(@Nullable AttributesGetters parent) {
        return new ImmutableAttributesBuilder(parent);
    }

    /**
     * Returns a new builder created from the entries of this {@link Attributes}.
     */
    AttributesBuilder toBuilder();

    /**
     * Returns a new {@link Attributes} which is the result from the mutation by the specified {@link Consumer}.
     * This method is a shortcut for:
     * <pre>{@code
     * builder = toBuilder();
     * mutator.accept(builder);
     * return builder.build();
     * }</pre>
     *
     * @see #toBuilder()
     */
    default Attributes withMutations(Consumer<AttributesBuilder> mutator) {
        final AttributesBuilder builder = toBuilder();
        mutator.accept(builder);
        return builder.build();
    }
}
