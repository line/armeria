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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.concurrent.CompletableFuture.completedFuture;

import java.util.concurrent.CompletableFuture;

import com.google.common.util.concurrent.RateLimiter;

import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * A {@link ThrottlingStrategy} that provides a throttling strategy based on QPS.
 * The throttling works by examining the number of requests from the {@link ThrottlingService} from
 * the beginning, and throttling if the QPS is found exceed the specified tolerable maximum.
 */
public final class RateLimitingThrottlingStrategy<T extends Request> extends ThrottlingStrategy<T> {
    private final RateLimiter rateLimiter;

    /**
     * Creates a new strategy.
     *
     * @param requestPerSecond a number of requests per one second this {@link ThrottlingStrategy} accepts.
     */
    public RateLimitingThrottlingStrategy(int requestPerSecond) {
        checkArgument(requestPerSecond > 0, "requestPerSecond: %s (expected: > 0)", requestPerSecond);
        rateLimiter = RateLimiter.create(requestPerSecond);
    }

    @Override
    public CompletableFuture<Boolean> accept(ServiceRequestContext ctx, T request) {
        return completedFuture(rateLimiter.tryAcquire());
    }
}
