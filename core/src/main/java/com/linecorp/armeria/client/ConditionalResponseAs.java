package com.linecorp.armeria.client;

import java.util.function.Predicate;

public interface ConditionalResponseAs<T, R, V> {

    /**
     * Adds the mapping of {@link ResponseAs} and {@link Predicate} to the List.
     */
    ConditionalResponseAs<T, R, V> andThen(ResponseAs<R, V> responseAs, Predicate<R> predicate);

    /**
     * Returns {@link ResponseAs} whose {@link Predicate} is evaluated as true.
     */
    ResponseAs<T, V> orElse(ResponseAs<R, V> responseAs);
}
