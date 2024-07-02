/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.internal.client.endpoint.healthcheck;

import static com.google.common.collect.ImmutableList.toImmutableList;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.healthcheck.HealthCheckerContext;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.AsyncCloseable;
import com.linecorp.armeria.common.util.Exceptions;

public final class HealthCheckContextGroup {
    private static final CompletableFuture<?>[] EMPTY_FUTURES = new CompletableFuture[0];

    private final Map<Endpoint, DefaultHealthCheckerContext> contexts;
    private final List<Endpoint> candidates;
    private final Function<? super HealthCheckerContext, ? extends AsyncCloseable> checkerFactory;

    @Nullable
    private CompletableFuture<?> initFutures;

    HealthCheckContextGroup(Map<Endpoint, DefaultHealthCheckerContext> contexts, List<Endpoint> candidates,
                            Function<? super HealthCheckerContext, ? extends AsyncCloseable> checkerFactory) {
        this.contexts = Collections.unmodifiableMap(contexts);
        this.candidates = candidates;
        this.checkerFactory = checkerFactory;
    }

    @VisibleForTesting
    public Map<Endpoint, DefaultHealthCheckerContext> contexts() {
        return contexts;
    }

    @VisibleForTesting
    public List<Endpoint> candidates() {
        return candidates.stream().map(endpoint -> {
            final DefaultHealthCheckerContext context = contexts.get(endpoint);
            assert context != null;
            return endpoint.withAttrs(context.endpointAttributes());
        }).collect(Collectors.toList());
    }

    void initialize() {
        final List<CompletableFuture<Void>> futures =
                contexts.values().stream()
                        .peek(context -> {
                            if (!context.initializationStarted()) {
                                // A newly created context
                                context.init(checkerFactory.apply(context));
                            }
                        })
                        .map(DefaultHealthCheckerContext::whenInitialized)
                        .collect(toImmutableList());

        initFutures = CompletableFuture.allOf(futures.toArray(EMPTY_FUTURES)).handle((unused, cause) -> {
            if (cause == null) {
                return null;
            }
            if (futures.isEmpty()) {
                return null;
            }

            if (futures.stream().anyMatch(future -> !future.isCompletedExceptionally())) {
                // There is at least one success
                return null;
            }

            Throwable combined = null;
            for (CompletableFuture<Void> future : futures) {
                try {
                    future.join();
                } catch (Throwable ex) {
                    if (combined == null) {
                        combined = ex;
                    } else {
                        combined.addSuppressed(Exceptions.peel(ex));
                    }
                }
            }
            assert combined != null;
            return Exceptions.throwUnsafely(combined);
        });
    }

    @VisibleForTesting
    public CompletableFuture<?> whenInitialized() {
        assert initFutures != null : "Should call initialize() before invoking this method.";
        return initFutures;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("contexts", contexts)
                          .add("candidates", candidates)
                          .add("initialized", initFutures != null && initFutures.isDone())
                          .toString();
    }
}
