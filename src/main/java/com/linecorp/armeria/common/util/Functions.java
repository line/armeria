/*
 * Copyright 2015 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.common.util;

import static java.util.Objects.requireNonNull;

import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Utility methods related with function composition.
 */
public final class Functions {

    /**
     * Returns a {@link Function} that composes the specified {@link Function}s with chained
     * {@link Function#andThen(Function)}. It is useful when you need to compose method handles, which is
     * impossible with {@link Function#andThen(Function)}:
     * <pre>{@code
     * Function<A, B> f = Functions.compose(TypeA::new, TypeB::new);
     * }</pre>
     */
    public static <A, B, C> Function<A, C> compose(Function<A, B> f1, Function<B, C> f2) {
        return requireNonNull(f1, "f1").andThen(requireNonNull(f2, "f2"));
    }

    /**
     * Returns a {@link Function} that composes the specified {@link Function}s with chained
     * {@link Function#andThen(Function)}. It is useful when you need to compose method handles, which is
     * impossible with {@link Function#andThen(Function)}:
     * <pre>{@code
     * Function<A, B> f = Functions.compose(TypeA::new, TypeB::new);
     * }</pre>
     */
    public static <A, B, C, D> Function<A, D> compose(Function<A, B> f1, Function<B, C> f2,
                                                      Function<C, D> f3) {

        return requireNonNull(f1, "f1").andThen(requireNonNull(f2, "f2"))
                                       .andThen(requireNonNull(f3, "f3"));
    }

    /**
     * Returns a {@link Function} that composes the specified {@link Function}s with chained
     * {@link Function#andThen(Function)}. It is useful when you need to compose method handles, which is
     * impossible with {@link Function#andThen(Function)}:
     * <pre>{@code
     * Function<A, B> f = Functions.compose(TypeA::new, TypeB::new);
     * }</pre>
     */
    public static <A, B, C, D, E> Function<A, E> compose(Function<A, B> f1, Function<B, C> f2,
                                                         Function<C, D> f3, Function<D, E> f4) {

        return requireNonNull(f1, "f1").andThen(requireNonNull(f2, "f2"))
                                       .andThen(requireNonNull(f3, "f3"))
                                       .andThen(requireNonNull(f4, "f4"));
    }

    /**
     * Returns a {@link Function} that composes the specified {@link Function}s with chained
     * {@link Function#andThen(Function)}. It is useful when you need to compose method handles, which is
     * impossible with {@link Function#andThen(Function)}:
     * <pre>{@code
     * Function<A, B> f = Functions.compose(TypeA::new, TypeB::new);
     * }</pre>
     */
    public static <A, B, C, D, E, F> Function<A, F> compose(Function<A, B> f1, Function<B, C> f2,
                                                            Function<C, D> f3, Function<D, E> f4,
                                                            Function<E, F> f5) {

        return requireNonNull(f1, "f1").andThen(requireNonNull(f2, "f2"))
                                       .andThen(requireNonNull(f3, "f3"))
                                       .andThen(requireNonNull(f4, "f4"))
                                       .andThen(requireNonNull(f5, "f5"));
    }

    /**
     * Returns a {@link Function} that composes the specified {@link Function}s with chained
     * {@link Function#andThen(Function)}. It is useful when you need to compose method handles, which is
     * impossible with {@link Function#andThen(Function)}:
     * <pre>{@code
     * Function<A, B> f = Functions.compose(TypeA::new, TypeB::new);
     * }</pre>
     */
    public static <A, B, C, D, E, F, G> Function<A, G> compose(Function<A, B> f1, Function<B, C> f2,
                                                               Function<C, D> f3, Function<D, E> f4,
                                                               Function<E, F> f5, Function<F, G> f6) {
        return requireNonNull(f1, "f1").andThen(requireNonNull(f2, "f2"))
                                       .andThen(requireNonNull(f3, "f3"))
                                       .andThen(requireNonNull(f4, "f4"))
                                       .andThen(requireNonNull(f5, "f5"))
                                       .andThen(requireNonNull(f6, "f6"));
    }

    /**
     * Returns a {@link Function} that composes the specified {@link Function}s with chained
     * {@link Function#andThen(Function)}. It is useful when you need to compose method handles, which is
     * impossible with {@link Function#andThen(Function)}:
     * <pre>{@code
     * Function<A, B> f = Functions.compose(TypeA::new, TypeB::new);
     * }</pre>
     */
    public static <A, B, C, D, E, F, G, H> Function<A, H> compose(Function<A, B> f1, Function<B, C> f2,
                                                                  Function<C, D> f3, Function<D, E> f4,
                                                                  Function<E, F> f5, Function<F, G> f6,
                                                                  Function<G, H> f7) {

        return requireNonNull(f1, "f1").andThen(requireNonNull(f2, "f2"))
                                       .andThen(requireNonNull(f3, "f3"))
                                       .andThen(requireNonNull(f4, "f4"))
                                       .andThen(requireNonNull(f5, "f5"))
                                       .andThen(requireNonNull(f6, "f6"))
                                       .andThen(requireNonNull(f7, "f7"));
    }

    /**
     * Returns a {@link Function} that composes the specified {@link Function}s with chained
     * {@link Function#andThen(Function)}. It is useful when you need to compose method handles, which is
     * impossible with {@link Function#andThen(Function)}:
     * <pre>{@code
     * Function<A, B> f = Functions.compose(TypeA::new, TypeB::new);
     * }</pre>
     */
    public static <A, B, C, D, E, F, G, H, I> Function<A, I> compose(Function<A, B> f1, Function<B, C> f2,
                                                                     Function<C, D> f3, Function<D, E> f4,
                                                                     Function<E, F> f5, Function<F, G> f6,
                                                                     Function<G, H> f7, Function<H, I> f8) {

        return requireNonNull(f1, "f1").andThen(requireNonNull(f2, "f2"))
                                       .andThen(requireNonNull(f3, "f3"))
                                       .andThen(requireNonNull(f4, "f4"))
                                       .andThen(requireNonNull(f5, "f5"))
                                       .andThen(requireNonNull(f6, "f6"))
                                       .andThen(requireNonNull(f7, "f7"))
                                       .andThen(requireNonNull(f8, "f8"));
    }

    /**
     * Returns a {@link Function} that composes the specified {@link Function}s with chained
     * {@link Function#andThen(Function)}. It is useful when you need to compose method handles, which is
     * impossible with {@link Function#andThen(Function)}:
     * <pre>{@code
     * Function<A, B> f = Functions.compose(TypeA::new, TypeB::new);
     * }</pre>
     */
    public static <A, B, C, D, E, F, G, H, I, J> Function<A, J> compose(Function<A, B> f1, Function<B, C> f2,
                                                                        Function<C, D> f3, Function<D, E> f4,
                                                                        Function<E, F> f5, Function<F, G> f6,
                                                                        Function<G, H> f7, Function<H, I> f8,
                                                                        Function<I, J> f9) {

        return requireNonNull(f1, "f1").andThen(requireNonNull(f2, "f2"))
                                       .andThen(requireNonNull(f3, "f3"))
                                       .andThen(requireNonNull(f4, "f4"))
                                       .andThen(requireNonNull(f5, "f5"))
                                       .andThen(requireNonNull(f6, "f6"))
                                       .andThen(requireNonNull(f7, "f7"))
                                       .andThen(requireNonNull(f8, "f8"))
                                       .andThen(requireNonNull(f9, "f9"));
    }

    /**
     * Converts the specified {@link Consumer} into a {@link Function} that returns {@code null}.
     */
    public static <T> Function<T, Void> voidFunction(Consumer<T> consumer) {
        return v -> {
            consumer.accept(v);
            return null;
        };
    }

    /**
     * Converts the specified {@link BiConsumer} into a {@link BiFunction} that returns {@code null}.
     */
    public static <T, U> BiFunction<T, U, Void> voidFunction(BiConsumer<T, U> consumer) {
        return (a, b) -> {
            consumer.accept(a, b);
            return null;
        };
    }

    private Functions() {}
}
