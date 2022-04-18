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

package com.linecorp.armeria.client.endpoint.healthcheck;

import static com.google.common.collect.ImmutableList.toImmutableList;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.AsyncCloseable;

final class HealthCheckContextGroup {
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

    Map<Endpoint, DefaultHealthCheckerContext> contexts() {
        return contexts;
    }

    List<Endpoint> candidates() {
        return candidates;
    }

    void initialize() {
        initFutures = CompletableFuture.allOf(contexts.values().stream()
                                                      .peek(context -> {
                                                          if (!context.isInitialized()) {
                                                              // A newly created context
                                                              context.init(checkerFactory.apply(context));
                                                          }
                                                      })
                                                      .map(DefaultHealthCheckerContext::whenInitialized)
                                                      .collect(toImmutableList())
                                                      .toArray(EMPTY_FUTURES));
    }

    CompletableFuture<?> whenInitialized() {
        assert initFutures != null : "Should call initialize() before invoking this method.";
        return initFutures;
    }
}
