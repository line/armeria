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
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Provides a way to unwrap an object in decorator pattern, similar to down-casting in an inheritance pattern.
 */
public interface Unwrappable {
    /**
     * Unwraps this object into the object of the specified {@code type}.
     * Use this method instead of an explicit downcast. For example:
     * <pre>{@code
     * class Foo {}
     *
     * class Bar<T> extends AbstractWrapper<T> {
     *     Bar(T delegate) {
     *         super(delegate);
     *     }
     * }
     *
     * class Qux<T> extends AbstractWrapper<T> {
     *     Qux(T delegate) {
     *         super(delegate);
     *     }
     * }
     *
     * Qux qux = new Qux(new Bar(new Foo()));
     * Foo foo = qux.as(Foo.class);
     * Bar bar = qux.as(Bar.class);
     * }</pre>
     *
     * @param type the type of the object to return
     * @return the object of the specified {@code type} if found, or {@code null} if not found.
     */
    @Nullable
    default <T> T as(Class<T> type) {
        requireNonNull(type, "type");
        return type.isInstance(this) ? type.cast(this) : null;
    }

    /**
     * Unwraps this object and returns the object being decorated. If this {@link Unwrappable} is the innermost
     * object, this method returns itself. For example:
     * <pre>{@code
     * class Foo implements Unwrappable {}
     *
     * class Bar<T extends Unwrappable> extends AbstractUnwrappable<T> {
     *     Bar(T delegate) {
     *         super(delegate);
     *     }
     * }
     *
     * class Qux<T extends Unwrappable> extends AbstractUnwrappable<T> {
     *     Qux(T delegate) {
     *         super(delegate);
     *     }
     * }
     *
     * Foo foo = new Foo();
     * assert foo.unwrap() == foo;
     *
     * Bar<Foo> bar = new Bar<>(foo);
     * assert bar.unwrap() == foo;
     *
     * Qux<Bar<Foo>> qux = new Qux<>(bar);
     * assert qux.unwrap() == bar;
     * assert qux.unwrap().unwrap() == foo;
     * }</pre>
     */
    default Unwrappable unwrap() {
        return this;
    }

    /**
     * Unwraps this object and returns the innermost object being decorated.
     * If this {@link Unwrappable} is the innermost object, this method returns itself. For example:
     * <pre>{@code
     * class Foo implements Unwrappable {}
     *
     * class Bar<T extends Unwrappable> extends AbstractUnwrappable<T> {
     *     Bar(T delegate) {
     *         super(delegate);
     *     }
     * }
     *
     * class Qux<T extends Unwrappable> extends AbstractUnwrappable<T> {
     *     Qux(T delegate) {
     *         super(delegate);
     *     }
     * }
     *
     * Foo foo = new Foo();
     * assert foo.unwrapAll() == foo;
     *
     * Bar<Foo> bar = new Bar<>(foo);
     * assert bar.unwrapAll() == foo;
     *
     * Qux<Bar<Foo>> qux = new Qux<>(bar);
     * assert qux.unwrap() == bar;
     * assert qux.unwrapAll() == foo;
     * }</pre>
     */
    @UnstableApi
    default Object unwrapAll() {
        Unwrappable unwrapped = this;
        while (true) {
            final Unwrappable inner = unwrapped.unwrap();
            if (inner == unwrapped) {
                return inner;
            }
            unwrapped = inner;
        }
    }

    /**
     * Reference checking this {@link Unwrappable} to another {@link Unwrappable}, ignoring wrappers.
     * Two {@link Unwrappable} are considered equal ignoring wrappers if they are of the same object reference
     * after {@link #unwrapAll()}.
     * @param other The {@link Unwrappable} to compare this {@link Unwrappable} against
     * @return true if the argument is not {@code null}, and it represents a same object reference after
     *         {@code unwrapAll()}, false otherwise.
     */
    @UnstableApi
    default boolean equalsIgnoreWrapper(@Nullable Unwrappable other) {
        if (other == null) {
            return false;
        }
        return unwrapAll() == other.unwrapAll();
    }
}
