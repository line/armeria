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

import javax.annotation.Nullable;

import com.google.common.base.MoreObjects;

/**
 * A value mapped by {@link Router}.
 *
 * @param <T> the type of the mapped value
 */
public final class RouteElement<T> {

    private static final RouteElement<Object> EMPTY = new RouteElement<>(null, RouteResult.empty(), null);

    /**
     * Returns a singleton instance of a {@link RouteElement} that represents a non-existent value.
     */
    @SuppressWarnings("unchecked")
    public static <T> RouteElement<T> empty() {
        return (RouteElement<T>) EMPTY;
    }

    /**
     * Creates a new {@link RouteElement} with the specified {@link Route}, {@link RouteResult} and
     * {@code value}.
     */
    static <T> RouteElement<T> of(Route route, RouteResult routeResult, T value) {
        requireNonNull(route, "route");
        requireNonNull(routeResult, "routeResult");
        requireNonNull(value, "value");
        if (!routeResult.isPresent()) {
            throw new IllegalArgumentException("routeResult: " + routeResult + " (must be present)");
        }

        return new RouteElement<>(route, routeResult, value);
    }

    @Nullable
    private final Route route;
    private final RouteResult routeResult;
    @Nullable
    private final T value;

    private RouteElement(@Nullable Route route, RouteResult routeResult, @Nullable T value) {
        assert route != null && value != null ||
               route == null && value == null;

        this.route = route;
        this.routeResult = routeResult;
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
     * Returns the {@link RouteResult}.
     *
     * @throws IllegalStateException if there's no match
     */
    public RouteResult routeResult() {
        ensurePresence();
        return routeResult;
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
                                  .add("routeResult", routeResult)
                                  .add("value", value)
                                  .toString();
        } else {
            return getClass().getSimpleName() + "{<empty>}";
        }
    }
}
