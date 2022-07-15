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
 * Sets entries for building an {@link Attributes} or updating {@link ConcurrentAttributes}.
 */
@UnstableApi
public interface AttributesSetters {

    /**
     * Sets the specified value with the given {@link AttributeKey}.
     * The old value associated with the {@link AttributeKey} is replaced by the specified value.
     * If a {@code null} value is specified, the value in the {@link AttributesGetters#parent()} is hidden as
     * well.
     *
     * <pre>{@code
     * static final AttributeKey<String> USER_ID = AttributeKey.valueOf("USER_ID");
     * static final AttributeKey<String> SECRET_TOKEN = AttributeKey.valueOf("SECRET_TOKEN");
     * static final AttributeKey<String> TRACE_ID = AttributeKey.valueOf("TRACE_ID");
     *
     * Attributes attributes = Attributes.of(USER_ID, "Meri Kim",
     *                                       SECRET_TOKEN, "secret-1",
     *                                       TRACE_ID, "trace-1");
     *
     * Attributes child = Attributes.builder(attributes)
     *                              .set(SECRET_TOKEN, null)
     *                              .set(TRACE_ID, "trace-2")
     *                              .build();
     *
     * // Any mutations in the child do not modify the value in the parent.
     * assert attributes.attr(USER_ID).equals("Meri Kim");
     * assert attributes.attr(SECRET_TOKEN).equals("secret-1");
     * assert attributes.attr(TRACE_ID).equals("trace-1");
     *
     * // Inherits the value of USER_ID from the parent.
     * assert child.attr(USER_ID).equals("Meri Kim");
     * // Hides the value of SECRET_TOKEN that the parent has.
     * assert child.attr(SECRET_TOKEN) == null;
     * // Overrides the parent's TRACE_ID.
     * assert child.attr(TRACE_ID).equals("trace-2");
     * }</pre>
     */
    <T> AttributesSetters set(AttributeKey<T> key, @Nullable T value);

    /**
     * Sets the specified value with the given {@link AttributeKey}.
     * The old value associated with the {@link AttributeKey} is replaced by the specified value.
     * If a {@code null} value is specified, the value in the {@link AttributesGetters#parent()} is hidden as
     * well.
     *
     * <pre>{@code
     * static final AttributeKey<String> USER_ID = AttributeKey.valueOf("USER_ID");
     * static final AttributeKey<String> SECRET_TOKEN = AttributeKey.valueOf("SECRET_TOKEN");
     * static final AttributeKey<String> TRACE_ID = AttributeKey.valueOf("TRACE_ID");
     *
     * Attributes attributes = Attributes.of(USER_ID, "Meri Kim",
     *                                       SECRET_TOKEN, "secret-1",
     *                                       TRACE_ID, "trace-1");
     *
     * AttributesBuilder newAttributesBuilder = attributes.toBuilder();
     * assert newAttributesBuilder.getAndSet(SECRET_TOKEN, null).equals("secret-1")
     * assert newAttributesBuilder.getAndSet(TRACE, "trace-2").equals("trace-2")
     * Attributes newAttributes = newAttributesBuilder.build();
     *
     * // Any mutations in the child do not modify the value in the original attributes.
     * assert attributes.attr(USER_ID).equals("Meri Kim");
     * assert attributes.attr(SECRET_TOKEN).equals("secret-1");
     * assert attributes.attr(TRACE_ID).equals("trace-1");
     *
     * // Copies the value from the original attribute.
     * assert newAttributes.attr(USER_ID).equals("Meri Kim");
     * // Removes the value of the original attribute.
     * assert newAttributes.attr(SECRET_TOKEN) == null;
     * // Overrides the value of the original attribute.
     * assert newAttributes.attr(TRACE_ID).equals("trace-2");
     * }</pre>
     *
     * @return the previous value associated with the {@link AttributeKey},
     *         or {@code null} if there was no mapping for the {@link AttributeKey}.
     *         A {@code null} can be returned if the {@link AttributeKey} is previously
     *         associated with {@code null}.
     */
    @Nullable
    <T> T getAndSet(AttributeKey<T> key, @Nullable T value);

    /**
     * Removes the value associated with the specified {@link AttributeKey} in the
     * {@link AttributesGetters#ownAttrs()}.
     *
     * <p>Note that this method won't remove the value in {@link AttributesGetters#parent()}.
     *
     * <pre>{@code
     * static final AttributeKey<String> USER_ID = AttributeKey.valueOf("USER_ID");
     * static final AttributeKey<String> SECRET_TOKEN = AttributeKey.valueOf("SECRET_TOKEN");
     * static final AttributeKey<String> TRACE_ID = AttributeKey.valueOf("TRACE_ID");
     *
     * Attributes attributes = Attributes.of(USER_ID, "Meri Kim", SECRET_TOKEN, "secret-1");
     *
     * AttributesBuilder newAttributesBuilder = attributes.toBuilder();
     * assert newAttributesBuilder.remove(USER_ID);
     * assert !newAttributesBuilder.remove(TRACE_ID);
     *
     * AttributesBuilder childAttributes =
     *     Attributes.builder(attributes)
     *               .set(TRACE_ID, "secret-2")
     *               .remove(USER_ID)
     *               .remove(TRACE_ID)
     *               .build();
     *
     * assert attributes.attr(TRACE_ID) == null;
     * // The value in the parent will not be removed.
     * assert attributes.attr(USER_ID).equals("Meri Kim");
     * }</pre>
     *
     * @return {@code true} if the value associated the {@link AttributeKey} has been removed.
     */
    <T> boolean remove(AttributeKey<T> key);

    /**
     * Removes the value associated with the specified {@link AttributeKey} in the
     * {@link AttributesGetters#ownAttrs()}. Unlike {@link #remove(AttributeKey)} this method returns itself
     * so that the caller can chain the invocations.
     *
     * <p>Note that this method won't remove the value in {@link Attributes#parent()}.
     */
    default <T> AttributesSetters removeAndThen(AttributeKey<T> key) {
        remove(key);
        return this;
    }
}
