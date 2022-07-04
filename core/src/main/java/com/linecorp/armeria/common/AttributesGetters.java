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

import java.util.Iterator;
import java.util.Map.Entry;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.netty.util.AttributeKey;

/**
 * Provides the getter methods to {@link Attributes} and {@link ConcurrentAttributes}.
 */
@UnstableApi
public interface AttributesGetters {

    /**
     * Returns the value associated with the given {@link AttributeKey} or {@code null} if there's no value
     * set by {@link AttributesSetters#set(AttributeKey, Object)}.
     * If there is no value in this {@link AttributesGetters} but it exists in {@link #parent()},
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
     * Returns the {@link Iterator} of all {@link Entry}s this {@link AttributesGetters} contains.
     *
     * <p>Unlike {@link #attrs()}, this does not iterate {@link #parent()}}.</p>
     * For example:
     * <pre>{@code
     * static final AttributeKey<String> USER_ID = AttributeKey.valueOf("USER_ID");
     * static final AttributeKey<String> SECRET_TOKEN = AttributeKey.valueOf("SECRET_TOKEN");
     * static final AttributeKey<String> TRACE_ID = AttributeKey.valueOf("TRACE_ID");
     *
     * Attributes parent = Attributes.of(USER_ID, "Meri Kim",
     *                                   SECRET_TOKEN, "secret-1");
     *
     * Attributes child = Attributes.builder(parent)
     *                              .set(SECRET_TOKEN, "secret-2")
     *                              .set(TRACE_ID, "trace-1")
     *                              .build();
     *
     * Iterator<Entry<AttributeKey<?>, Object>> attrs = child.ownAttrs();
     * assert Iterables.size(attrs) == 2;
     * assert Streams.stream(child.attrs())
     *               .map(Entry::getValue)
     *               .sorted()
     *               .collect(Collectors.toList())
     *               .equals(List.of("secret-2", "trace-1"));
     * }</pre>
     *
     * @see #attrs()
     */
    Iterator<Entry<AttributeKey<?>, Object>> ownAttrs();

    /**
     * Returns the {@link Iterator} of all {@link Entry}s this {@link AttributesGetters} contains.
     *
     * <p>The {@link Iterator} returned by this method will also yield the {@link Entry}s from the
     * {@link #parent()}} except those whose {@link AttributeKey} exist already in this context.
     * For example:
     * <pre>{@code
     * static final AttributeKey<String> USER_ID = AttributeKey.valueOf("USER_ID");
     * static final AttributeKey<String> SECRET_TOKEN = AttributeKey.valueOf("SECRET_TOKEN");
     * static final AttributeKey<String> TRACE_ID = AttributeKey.valueOf("TRACE_ID");
     *
     * Attributes parent = Attributes.of(USER_ID, "Meri Kim",
     *                                   SECRET_TOKEN, "secret-1");
     *
     * Attributes child = Attributes.builder(parent)
     *                              .set(SECRET_TOKEN, "secret-2")
     *                              .set(TRACE_ID, "trace-1")
     *                              .build();
     *
     * Iterator<Entry<AttributeKey<?>, Object>> attrs = child.attrs();
     * assert Iterables.size(attrs) == 3;
     * assert Streams.stream(child.attrs())
     *               .map(Entry::getValue)
     *               .sorted()
     *               .collect(Collectors.toList())
     *               // "secret-1" is overridden by "secret-2"
     *               .equals(List.of("Meri Kim", "secret-2", "trace-1"));
     * }</pre>
     */
    Iterator<Entry<AttributeKey<?>, Object>> attrs();

    /**
     * Returns the {@link #parent()} which was specified when creating this {@link AttributesGetters}.
     *
     * @see Attributes#fromParent(AttributesGetters)
     * @see Attributes#builder(AttributesGetters)
     * @see ConcurrentAttributes#fromParent(AttributesGetters)
     */
    @Nullable
    AttributesGetters parent();

    /**
     * Returns {@code true} if this {@link AttributesGetters} does not contain any entries.
     */
    boolean isEmpty();

    /**
     * Returns the number of {@link AttributeKey}-value mappings in this {@link AttributesGetters}.
     *
     * <p>If the same {@link AttributeKey} is both in the {@link #parent()} and the child {@link Attributes},
     * only the {@link AttributeKey}-value mapping in the child will be counted.
     * <pre>{@code
     * static final AttributeKey<String> USER_ID = AttributeKey.valueOf("USER_ID");
     * static final AttributeKey<String> SECRET_TOKEN = AttributeKey.valueOf("SECRET_TOKEN");
     * static final AttributeKey<String> TRACE_ID = AttributeKey.valueOf("TRACE_ID");
     *
     * Attributes parent = Attributes.of(USER_ID, "Meri Kim",
     *                                   SECRET_TOKEN, "secret-1");
     * assert parent.size() == 2;
     *
     * Attributes child = Attributes.builder(parent)
     *                              .set(SECRET_TOKEN, "secret-2")
     *                              .set(TRACE_ID, "trace-1")
     *                              .build();
     * assert child.size() == 3;
     * }</pre>
     */
    int size();
}
