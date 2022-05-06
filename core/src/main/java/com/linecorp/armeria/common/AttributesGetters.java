/*
 *  Copyright 2022 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.common;

import java.util.Iterator;
import java.util.Map.Entry;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.netty.util.AttributeKey;

/**
 * Provides the getter methods to {@link Attributes} and {@link AttributesBuilder}.
 */
@UnstableApi
public interface AttributesGetters {

    /**
     * Returns the value associated with the given {@link AttributeKey} or {@code null} if there's no value
     * set by {@link AttributesBuilder#set(AttributeKey, Object)}.
     * If there is no value in this {@link Attributes} but it exists in {@link #parent()},
     * the value in the {@link #parent()}} will be returned.
     *
     * @see #ownAttr(AttributeKey)
     */
    @Nullable <T> T attr(AttributeKey<T> key);

    /**
     * Returns the value associated with the given {@link AttributeKey} or {@code null} if there's no value
     * set by {@link AttributesBuilder#set(AttributeKey, Object)}.
     *
     * <p>Unlike {@link #attr(AttributeKey)}, this does not search in {@link #parent()}.</p>
     *
     * @see #attr(AttributeKey)
     */
    @Nullable <T> T ownAttr(AttributeKey<T> key);

    /**
     * Returns {@code true} if and only if the value associated with the specified {@link AttributeKey} is
     * not {@code null}.
     *
     * @see #hasOwnAttr(AttributeKey)
     */
    default boolean hasAttr(AttributeKey<?> key) {
        return attr(key) != null;
    }

    /**
     * Returns {@code true} if and only if the value associated with the specified {@link AttributeKey} is
     * not {@code null}.
     *
     * <p>Unlike {@link #hasAttr(AttributeKey)}, this does not search in {@link #parent()}.</p>
     *
     * @see #hasAttr(AttributeKey)
     */
    default boolean hasOwnAttr(AttributeKey<?> key) {
        return ownAttr(key) != null;
    }

    /**
     * Returns the {@link Iterator} of all {@link Entry}s this {@link Attributes} contains.
     *
     * <p>Unlike {@link #attrs()}, this does not iterate {@link #parent()}}.</p>
     *
     * @see #attrs()
     */
    Iterator<Entry<AttributeKey<?>, Object>> ownAttrs();

    /**
     * Returns the {@link Iterator} of all {@link Entry}s this {@link Attributes} contains.
     *
     * <p>The {@link Iterator} returned by this method will also yield the {@link Entry}s from the
     * {@link #parent()}} except those whose {@link AttributeKey} exist already in this context, e.g.
     */
    Iterator<Entry<AttributeKey<?>, Object>> attrs();

    /**
     * Returns the {@link #parent()} which was specified when creating this {@link Attributes}.
     *
     * @see Attributes#of(AttributesGetters)
     * @see Attributes#builder(AttributesGetters)
     */
    @Nullable
    AttributesGetters parent();

    /**
     * Returns {@code true} if this {@link Attributes} does not contain any entries.
     */
    boolean isEmpty();
}
