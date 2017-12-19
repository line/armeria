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

import javax.annotation.Nullable;

import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * Determines whether a request should be throttled.
 */
public abstract class ThrottlingStrategy<T extends Request> {
    private static final AtomicInteger GLOBAL_STRATEGY_ID = new AtomicInteger();

    private static final ThrottlingStrategy<?> NEVER =
            new ThrottlingStrategy<Request>("throttling-strategy-never") {
                @Override
                public CompletableFuture<Boolean> accept(ServiceRequestContext ctx, Request request) {
                    return completedFuture(false);
                }
            };

    private static final ThrottlingStrategy<?> ALWAYS =
            new ThrottlingStrategy<Request>("throttling-strategy-always") {
                @Override
                public CompletableFuture<Boolean> accept(ServiceRequestContext ctx, Request request) {
                    return completedFuture(true);
                }
            };

    private final String name;

    /**
     * Creates a new anonymous {@link ThrottlingStrategy}.
     */
    protected ThrottlingStrategy() {
        this(null);
    }

    /**
     * Creates a new {@link ThrottlingStrategy} with specified name.
     */
    protected ThrottlingStrategy(@Nullable String name) {
        if (name != null) {
            this.name = name;
        } else {
            this.name = "throttling-strategy-" +
                        (getClass().isAnonymousClass() ? Integer.toString(GLOBAL_STRATEGY_ID.getAndIncrement())
                                                       : getClass().getSimpleName());
        }
    }

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
            BiFunction<ServiceRequestContext, T, CompletableFuture<Boolean>> function,
            String strategyName) {
        return new ThrottlingStrategy<T>(strategyName) {
            @Override
            public CompletableFuture<Boolean> accept(ServiceRequestContext ctx, T request) {
                return function.apply(ctx, request);
            }
        };
    }

    /**
     * Creates a new {@link ThrottlingStrategy} that determines whether a request should be accepted or not
     * using a given {@link BiFunction} instance.
     */
    public static <T extends Request> ThrottlingStrategy<T> of(
            BiFunction<ServiceRequestContext, T, CompletableFuture<Boolean>> function) {
        return new ThrottlingStrategy<T>(null) {
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
        return name;
    }
}
