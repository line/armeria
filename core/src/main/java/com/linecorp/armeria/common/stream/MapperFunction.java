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

package com.linecorp.armeria.common.stream;

import static java.util.Objects.requireNonNull;

import java.util.function.Function;
import java.util.function.Predicate;

import javax.annotation.Nullable;

/**
 * Represents either a {@link Function} or a {@link Predicate} depending on {@link #type()}.
 */
interface MapperFunction<T, R> extends Function<T, R> {

    /**
     * Creates a new {@link MapperFunction} from the specified {@link Function}.
     */
    static <T, R> MapperFunction<T, R> of(Function<? super T, ? extends R> function) {
        requireNonNull(function, "function");
        return new MapperFunction<T, R>() {

            @Override
            public R apply(T o) {
                final R result = function.apply(o);
                requireNonNull(result, "function.apply() returned null");
                return result;
            }

            @Override
            public Type type() {
                return Type.MAP;
            }
        };
    }

    /**
     * Creates a new {@link MapperFunction} from the specified {@link Predicate}.
     */
    static <T> MapperFunction<T, T> of(Predicate<? super T> predicate) {
        requireNonNull(predicate, "predicate");

        return new MapperFunction<T, T>() {

            @Override
            public T apply(T o) {
                final boolean result = predicate.test(o);
                if (result) {
                    return o;
                } else {
                    return null;
                }
            }

            @Override
            public Type type() {
                return Type.FILTER;
            }
        };
    }

    /**
     * Applies this function to the given argument.
     *
     * <li>
     *   <ul>{@link Type#FILTER} - {@code null} is returned if the give argument is filtered out.
     *                             Otherwise, the input is returned itself</ul>
     *   <ul>{@link Type#MAP} - the give argument is converted to another. {@code null} is not allowed</ul>
     * </li>
     */
    @Nullable
    @Override
    R apply(T t);

    enum Type {
        FILTER,
        MAP
    }

    /**
     * Returns the {@link Type} of this {@link MapperFunction}.
     */
    Type type();

}
