/*
 * Copyright 2017 LINE Corporation
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
package com.linecorp.armeria.server.throttling;

import static java.util.concurrent.CompletableFuture.completedFuture;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * Determines whether a request should be throttled.
 */
public abstract class ThrottlingStrategy<T extends Request> {
    private static final AtomicInteger GLOBAL_STRATEGY_ID = new AtomicInteger();
    private static final ThrottlingStrategy<?> NEVER = of((ctx, request) -> completedFuture(false));
    private static final ThrottlingStrategy<?> ALWAYS = of((ctx, request) -> completedFuture(true));

    private final int strategyId = GLOBAL_STRATEGY_ID.getAndIncrement();

    /**
     * Returns a singleton {@link ThrottlingStrategy} that never accepts requests.
     */
    @SuppressWarnings("unchecked")
    public static <T extends Request> ThrottlingStrategy<T> never() {
        return (ThrottlingStrategy<T>) NEVER;
    }

    /**
     * Returns a singleton {@link ThrottlingStrategy} that always accepts requests.
     */
    @SuppressWarnings("unchecked")
    public static <T extends Request> ThrottlingStrategy<T> always() {
        return (ThrottlingStrategy<T>) ALWAYS;
    }

    /**
     * Creates a new {@link ThrottlingStrategy} that determines whether a request should be accepted or not
     * using a given {@link BiFunction} instance.
     */
    public static <T extends Request> ThrottlingStrategy<T> of(
            BiFunction<ServiceRequestContext, T, CompletableFuture<Boolean>> function) {
        return new ThrottlingStrategy<T>() {
            @Override
            public CompletableFuture<Boolean> accept(ServiceRequestContext ctx, T request) {
                return function.apply(ctx, request);
            }
        };
    }

    /**
     * Returns whether a given request should be treated as failed before it is handled actually.
     */
    public abstract CompletableFuture<Boolean> accept(ServiceRequestContext ctx, T request);

    /**
     * Returns the name of this {@link ThrottlingStrategy}.
     */
    public String name() {
        return "throttling-strategy-" + strategyId;
    }
}
