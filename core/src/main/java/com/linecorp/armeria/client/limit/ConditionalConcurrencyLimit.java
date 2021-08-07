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

package com.linecorp.armeria.client.limit;

import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

import com.linecorp.armeria.client.ClientRequestContext;

/**
 * ConcurrencyLimit that applies the limit based a given {@code Predicate<ClientRequestContext>}.
 */
public class ConditionalConcurrencyLimit implements ConcurrencyLimit<ClientRequestContext> {
    private static final CompletableFuture<Permit> READY_PERMIT = CompletableFuture.completedFuture(
            () -> {
            });
    private final Predicate<? super ClientRequestContext> predicate;
    private final ConcurrencyLimit<ClientRequestContext> delegate;

    /**
     * Creates a new instance with the specified {@code predicate} and {@code delegage}.
     */
    public ConditionalConcurrencyLimit(Predicate<? super ClientRequestContext> predicate,
                                       ConcurrencyLimit<ClientRequestContext> delegate) {
        this.predicate = predicate;
        this.delegate = delegate;
    }

    @Override
    public CompletableFuture<Permit> acquire(ClientRequestContext ctx) {
        return predicate.test(ctx) ? delegate.acquire(ctx) : READY_PERMIT;
    }
}
