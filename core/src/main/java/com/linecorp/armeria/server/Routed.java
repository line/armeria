/*
 * Copyright 2015 LINE Corporation
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

package com.linecorp.armeria.server;

import static java.util.Objects.requireNonNull;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.annotation.Nullable;

/**
 * A value mapped by {@link Router}.
 *
 * @param <T> the type of the mapped value
 */
public final class Routed<T> {

    private static final Routed<Object> EMPTY = new Routed<>(null, RoutingResult.empty(), null);

    /**
     * Returns a singleton instance of a {@link Routed} that represents a non-existent value.
     */
    @SuppressWarnings("unchecked")
    public static <T> Routed<T> empty() {
        return (Routed<T>) EMPTY;
    }

    /**
     * Creates a new {@link Routed} with the specified {@link Route}, {@link RoutingResult} and
     * {@code value}.
     */
    static <T> Routed<T> of(Route route, RoutingResult routingResult, T value) {
        requireNonNull(route, "route");
        requireNonNull(routingResult, "routingResult");
        requireNonNull(value, "value");
        if (!routingResult.isPresent()) {
            throw new IllegalArgumentException("routingResult: " + routingResult + " (must be present)");
        }

        return new Routed<>(route, routingResult, value);
    }

    @Nullable
    private final Route route;
    private final RoutingResult routingResult;
    @Nullable
    private final T value;

    private Routed(@Nullable Route route, RoutingResult routingResult, @Nullable T value) {
        assert route != null && value != null ||
               route == null && value == null;

        this.route = route;
        this.routingResult = routingResult;
        this.value = value;
    }

    /**
     * Returns {@code true} if {@link Router} found a matching value.
     */
    public boolean isPresent() {
        return route != null;
    }

    /**
     * Returns the {@link Route} which matched the {@link RoutingContext}.
     *
     * @throws IllegalStateException if there's no match
     */
    public Route route() {
        ensurePresence();
        return route;
    }

    /**
     * Returns the {@link RoutingResult}.
     *
     * @throws IllegalStateException if there's no match
     */
    public RoutingResult routingResult() {
        ensurePresence();
        return routingResult;
    }

    /**
     * Returns the type of {@link RoutingResult}.
     */
    public RoutingResultType routingResultType() {
        return isPresent() ? routingResult.type() : RoutingResultType.NOT_MATCHED;
    }

    /**
     * Returns the value.
     *
     * @throws IllegalStateException if there's no match
     */
    public T value() {
        ensurePresence();
        return value;
    }

    private void ensurePresence() {
        if (!isPresent()) {
            throw new IllegalStateException("route unavailable");
        }
    }

    @Override
    public String toString() {
        if (isPresent()) {
            return MoreObjects.toStringHelper(this)
                                  .add("route", route)
                                  .add("routingResult", routingResult)
                                  .add("value", value)
                                  .toString();
        } else {
            return getClass().getSimpleName() + "{<empty>}";
        }
    }
}
