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

import static com.linecorp.armeria.internal.client.endpoint.healthcheck.HealthCheckAttributes.HEALTHY_ATTR;
import static com.linecorp.armeria.internal.common.util.CollectionUtil.truncate;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import com.spotify.futures.CompletableFutures;

import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.healthcheck.HealthCheckerContext;
import com.linecorp.armeria.client.endpoint.healthcheck.HealthCheckerParams;
import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.AbstractListenable;
import com.linecorp.armeria.common.util.AsyncCloseable;
import com.linecorp.armeria.internal.common.util.ReentrantShortLock;

public final class HealthCheckedEndpointPool extends AbstractListenable<List<Endpoint>>
        implements AsyncCloseable {

    private static final Logger logger = LoggerFactory.getLogger(HealthCheckedEndpointPool.class);

    private final Backoff retryBackoff;
    private final ClientOptions clientOptions;
    private final Function<? super HealthCheckerContext, ? extends AsyncCloseable> checkerFactory;

    private final ReentrantLock lock = new ReentrantShortLock(true);
    @GuardedBy("lock")
    private final Deque<HealthCheckContextGroup> contextGroupChain = new ArrayDeque<>(4);
    private volatile boolean initialized;
    Function<Endpoint, HealthCheckerParams> paramsFactory;

    public HealthCheckedEndpointPool(
            Backoff retryBackoff, ClientOptions clientOptions,
            Function<? super HealthCheckerContext, ? extends AsyncCloseable> checkerFactory,
            Function<Endpoint, HealthCheckerParams> paramsFactory) {
        this.retryBackoff = retryBackoff;
        this.clientOptions = clientOptions;
        this.checkerFactory = checkerFactory;
        this.paramsFactory = paramsFactory;
    }

    public void setEndpoints(List<Endpoint> endpoints) {
        final HashMap<Endpoint, DefaultHealthCheckerContext> contexts = new HashMap<>(endpoints.size());

        lock.lock();
        try {
            for (Endpoint endpoint : endpoints) {
                if (contexts.containsKey(endpoint)) {
                    continue;
                }
                final DefaultHealthCheckerContext context = findContext(endpoint);
                if (context != null) {
                    contexts.put(endpoint, context.retain());
                } else {
                    contexts.computeIfAbsent(endpoint, this::newCheckerContext);
                }
            }

            final HealthCheckContextGroup contextGroup = new HealthCheckContextGroup(contexts, endpoints,
                                                                                     checkerFactory);
            // 'updateHealth()' that retrieves 'contextGroupChain' could be invoked while initializing
            // HealthCheckerContext. For this reason, 'contexts' should be added to 'contextGroupChain'
            // before 'contextGroup.initialize()'.
            contextGroupChain.add(contextGroup);
            contextGroup.initialize();

            // Remove old contexts when the newly created contexts are fully initialized to smoothly transition
            // to new endpoints.
            contextGroup.whenInitialized().handle((unused, cause) -> {
                if (cause != null && !initialized) {
                    if (logger.isWarnEnabled()) {
                        logger.warn("The first health check failed for all endpoints. " +
                                    "numCandidates: {} candidates: {}",
                                    endpoints.size(), truncate(endpoints, 10), cause);
                    }
                }
                initialized = true;
                lock.lock();
                try {
                    destroyOldContexts(contextGroup);
                    notifyListeners();
                } finally {
                    lock.unlock();
                }
                return null;
            });
        } finally {
            lock.unlock();
        }
    }

    @Override
    @Nullable
    protected List<Endpoint> latestValue() {
        lock.lock();
        try {
            if (!initialized) {
                return null;
            }
            return ImmutableList.copyOf(allEndpoints());
        } finally {
            lock.unlock();
        }
    }

    @SuppressWarnings("GuardedBy")
    @VisibleForTesting
    public Queue<HealthCheckContextGroup> contextGroupChain() {
        return contextGroupChain;
    }

    @VisibleForTesting
    public List<Endpoint> healthyEndpoints() {
        return allEndpoints().stream().filter(endpoint -> Boolean.TRUE.equals(endpoint.attr(HEALTHY_ATTR)))
                             .collect(Collectors.toList());
    }

    @VisibleForTesting
    List<Endpoint> allEndpoints() {
        lock.lock();
        try {
            final HealthCheckContextGroup newGroup = contextGroupChain.peekLast();
            if (newGroup == null) {
                return ImmutableList.of();
            }

            final List<Endpoint> allEndpoints = new ArrayList<>(newGroup.candidates());

            for (HealthCheckContextGroup oldGroup : contextGroupChain) {
                if (oldGroup == newGroup) {
                    break;
                }
                for (Endpoint candidate : oldGroup.candidates()) {
                    if (!allEndpoints.contains(candidate)) {
                        // Add old Endpoints that do not exist in newGroup. When the first check for newGroup is
                        // completed, the old Endpoints will be removed.
                        allEndpoints.add(candidate);
                    }
                }
            }
            return allEndpoints;
        } finally {
            lock.unlock();
        }
    }

    @Nullable
    private DefaultHealthCheckerContext findContext(Endpoint endpoint) {
        lock.lock();
        try {
            for (HealthCheckContextGroup contextGroup : contextGroupChain) {
                final DefaultHealthCheckerContext context = contextGroup.contexts().get(endpoint);
                if (context != null) {
                    return context;
                }
            }
        } finally {
            lock.unlock();
        }
        return null;
    }

    private DefaultHealthCheckerContext newCheckerContext(Endpoint endpoint) {
        return new DefaultHealthCheckerContext(endpoint, clientOptions, retryBackoff,
                                               this::updateHealth, paramsFactory);
    }

    private void destroyOldContexts(HealthCheckContextGroup contextGroup) {
        lock.lock();
        try {
            if (!contextGroupChain.contains(contextGroup)) {
                // The contextGroup is already removed by another callback of `contextGroup.whenInitialized()`.
                return;
            }
            final Iterator<HealthCheckContextGroup> it = contextGroupChain.iterator();
            while (it.hasNext()) {
                final HealthCheckContextGroup maybeOldGroup = it.next();
                if (maybeOldGroup == contextGroup) {
                    break;
                }
                for (DefaultHealthCheckerContext context : maybeOldGroup.contexts().values()) {
                    context.release();
                }
                it.remove();
            }
        } finally {
            lock.unlock();
        }
    }

    private void updateHealth(Endpoint endpoint, boolean health) {
        if (!initialized) {
            return;
        }
        notifyListeners();
    }

    private void notifyListeners() {
        notifyListeners(allEndpoints());
    }

    @Override
    public CompletableFuture<?> closeAsync() {
        // Stop the health checkers in parallel.
        final CompletableFuture<?> stopFutures;
        lock.lock();
        try {
            final ImmutableList.Builder<CompletableFuture<?>> completionFutures = ImmutableList.builder();
            for (HealthCheckContextGroup group : contextGroupChain) {
                for (DefaultHealthCheckerContext context : group.contexts().values()) {
                    try {
                        final CompletableFuture<?> closeFuture = context.release();
                        if (closeFuture != null) {
                            completionFutures.add(closeFuture.exceptionally(cause -> {
                                logger.warn("Failed to stop a health checker for: {}",
                                            context.endpoint(), cause);
                                return null;
                            }));
                        }
                    } catch (Exception ex) {
                        logger.warn("Unexpected exception while closing a health checker for: {}",
                                    context.endpoint(), ex);
                    }
                }
            }
            stopFutures = CompletableFutures.allAsList(completionFutures.build());
        } finally {
            lock.unlock();
        }

        return stopFutures.handle((unused1, unused2) -> {
            lock.lock();
            try {
                contextGroupChain.clear();
            } finally {
                lock.unlock();
            }
            return null;
        });
    }

    @Override
    public void close() {
        closeAsync().join();
    }
}
