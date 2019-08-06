/*
 * Copyright 2016 LINE Corporation
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

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Function;

import javax.annotation.Nullable;

import org.jctools.maps.NonBlockingHashSet;
import org.jctools.maps.NonBlockingIdentityHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.MoreObjects;
import com.spotify.futures.CompletableFutures;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientOptionsBuilder;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.DynamicEndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.common.util.AsyncCloseable;

import io.micrometer.core.instrument.binder.MeterBinder;
import io.netty.util.concurrent.EventExecutorGroup;
import io.netty.util.concurrent.Future;

/**
 * An {@link EndpointGroup} that filters out unhealthy {@link Endpoint}s from an existing {@link EndpointGroup},
 * by sending periodic health check requests.
 */
public final class HealthCheckedEndpointGroup extends DynamicEndpointGroup {

    static final Backoff DEFAULT_HEALTH_CHECK_RETRY_BACKOFF = Backoff.fixed(3000).withJitter(0.2);

    private static final Logger logger = LoggerFactory.getLogger(HealthCheckedEndpointGroup.class);

    private static final AtomicReferenceFieldUpdater<HealthCheckedEndpointGroup, State> stateUpdater =
            AtomicReferenceFieldUpdater.newUpdater(HealthCheckedEndpointGroup.class, State.class, "state");

    /**
     * Returns a newly created {@link HealthCheckedEndpointGroup} that sends HTTP {@code HEAD} health check
     * requests with default options.
     *
     * @param delegate the {@link EndpointGroup} that provides the candidate {@link Endpoint}s
     * @param path     the HTTP request path, e.g. {@code "/internal/l7check"}
     */
    public static HealthCheckedEndpointGroup of(EndpointGroup delegate, String path) {
        return builder(delegate, path).build();
    }

    /**
     * Returns a newly created {@link HealthCheckedEndpointGroupBuilder} that builds
     * a {@link HealthCheckedEndpointGroup} which sends HTTP {@code HEAD} health check requests.
     *
     * @param delegate the {@link EndpointGroup} that provides the candidate {@link Endpoint}s
     * @param path     the HTTP request path, e.g. {@code "/internal/l7check"}
     */
    public static HealthCheckedEndpointGroupBuilder builder(EndpointGroup delegate, String path) {
        return new HealthCheckedEndpointGroupBuilder(delegate, path);
    }

    private enum State {
        UNINITIALIZED,
        INITIALIZED,
        CLOSED
    }

    final EndpointGroup delegate;
    private final ClientFactory clientFactory;
    private final SessionProtocol protocol;
    private final int port;
    private final Backoff retryBackoff;
    private final Function<? super ClientOptionsBuilder, ClientOptionsBuilder> clientConfigurator;
    private final Function<? super HealthCheckerContext, ? extends AsyncCloseable> checker;

    private final Map<Endpoint, DefaultHealthCheckerContext> contexts = new HashMap<>();
    private final Set<Endpoint> healthyEndpoints = new NonBlockingHashSet<>();
    @SuppressWarnings("FieldMayBeFinal") // Updated by `stateUpdater`
    private volatile State state = State.UNINITIALIZED;

    /**
     * Creates a new instance. Must call {@link #init()} before using.
     */
    HealthCheckedEndpointGroup(EndpointGroup delegate, ClientFactory clientFactory,
                               SessionProtocol protocol, int port, Backoff retryBackoff,
                               Function<? super ClientOptionsBuilder, ClientOptionsBuilder> clientConfigurator,
                               Function<? super HealthCheckerContext, ? extends AsyncCloseable> checker) {
        this.delegate = requireNonNull(delegate, "delegate");
        this.clientFactory = requireNonNull(clientFactory, "clientFactory");
        this.protocol = requireNonNull(protocol, "protocol");
        this.port = port;
        this.retryBackoff = requireNonNull(retryBackoff, "retryBackoff");
        this.clientConfigurator = requireNonNull(clientConfigurator, "clientConfigurator");
        this.checker = requireNonNull(checker, "checker");
    }

    /**
     * Update healthy servers and start to schedule health check. Must be called before using.
     */
    void init() {
        checkState(stateUpdater.compareAndSet(this, State.UNINITIALIZED, State.INITIALIZED),
                   "init() must only be called once at the end of the construction.");

        delegate.addListener(this::updateCandidates);
        updateCandidates(delegate.initialEndpointsFuture().join());

        // Wait until the initial health of all endpoints are determined.
        contexts.values().forEach(ctx -> ctx.initialCheckFuture.join());
    }

    private void updateCandidates(List<Endpoint> candidates) {
        synchronized (contexts) {
            if (state == State.CLOSED) {
                return;
            }

            // Stop the health checkers whose endpoints disappeared and destroy their contexts.
            for (final Iterator<Map.Entry<Endpoint, DefaultHealthCheckerContext>> i = contexts.entrySet()
                                                                                              .iterator();
                 i.hasNext();) {
                final Map.Entry<Endpoint, DefaultHealthCheckerContext> e = i.next();
                if (candidates.contains(e.getKey())) {
                    // Not a removed endpoint.
                    continue;
                }

                i.remove();
                e.getValue().destroy();
            }

            // Start the health checkers with new contexts for newly appeared endpoints.
            for (Endpoint e : candidates) {
                if (contexts.containsKey(e)) {
                    // Not a new endpoint.
                    continue;
                }
                final DefaultHealthCheckerContext ctx = new DefaultHealthCheckerContext(e);
                ctx.init(checker.apply(ctx));
                contexts.put(e, ctx);
            }
        }
    }

    @Override
    public void close() {
        final State oldState = stateUpdater.getAndSet(this, State.CLOSED);
        if (oldState == State.CLOSED) {
            return;
        }

        // Stop the health checkers in parallel.
        final CompletableFuture<List<Object>> stopFutures;
        synchronized (contexts) {
            stopFutures = CompletableFutures.allAsList(contexts.values().stream()
                                                               .map(DefaultHealthCheckerContext::destroy)
                                                               .collect(toImmutableList()));
            contexts.clear();
        }

        super.close();
        delegate.close();

        // Wait until the health checkers are fully stopped.
        try {
            stopFutures.join();
        } catch (Exception e) {
            logger.warn("Failed to stop all health checkers:", e);
        }
    }

    /**
     * Returns a newly-created {@link MeterBinder} which binds the stats about this
     * {@link HealthCheckedEndpointGroup} with the default meter names.
     */
    public MeterBinder newMeterBinder(String groupName) {
        return newMeterBinder(new MeterIdPrefix("armeria.client.endpointGroup", "name", groupName));
    }

    /**
     * Returns a newly-created {@link MeterBinder} which binds the stats about this
     * {@link HealthCheckedEndpointGroup}.
     */
    public MeterBinder newMeterBinder(MeterIdPrefix idPrefix) {
        return new HealthCheckedEndpointGroupMetrics(this, idPrefix);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("chosen", endpoints())
                          .add("candidates", delegate.endpoints())
                          .toString();
    }

    private final class DefaultHealthCheckerContext implements HealthCheckerContext {

        private final Endpoint endpoint;
        @Nullable
        private AsyncCloseable handle;
        final PerEndpointExecutor executor;
        final CompletableFuture<?> initialCheckFuture = new CompletableFuture<>();

        DefaultHealthCheckerContext(Endpoint endpoint) {
            this.endpoint = endpoint;
            executor = new PerEndpointExecutor(clientFactory.eventLoopGroup());
        }

        void init(AsyncCloseable handle) {
            assert this.handle == null;
            this.handle = handle;
        }

        @Override
        public Endpoint endpoint() {
            return endpoint;
        }

        @Override
        public ClientFactory clientFactory() {
            return clientFactory;
        }

        @Override
        public SessionProtocol protocol() {
            return protocol;
        }

        @Override
        public int port() {
            return port;
        }

        @Override
        public Function<? super ClientOptionsBuilder, ClientOptionsBuilder> clientConfigurator() {
            return clientConfigurator;
        }

        @Override
        public ScheduledExecutorService executor() {
            return executor;
        }

        @Override
        public long nextDelayMillis() {
            final long delayMillis = retryBackoff.nextDelayMillis(1);
            if (delayMillis < 0) {
                logger.warn("Health check might have stopped working due to a negative delayMillis ({}) " +
                            "returned by retryBackOff.nextDelayMillis(0).", delayMillis);
                return 0;
            }

            return delayMillis;
        }

        @Override
        public void updateHealth(double health) {
            final boolean updated;
            if (health > 0) {
                updated = healthyEndpoints.add(endpoint);
            } else {
                updated = healthyEndpoints.remove(endpoint);
            }

            if (updated) {
                // Rebuild the endpoint list and notify.
                setEndpoints(delegate.endpoints().stream()
                                     .filter(healthyEndpoints::contains)
                                     .collect(toImmutableList()));
            }

            initialCheckFuture.complete(null);
        }

        CompletableFuture<?> destroy() {
            assert handle != null : handle;
            return handle.closeAsync().handle((unused1, unused2) -> {
                executor.cancelScheduledFutures();
                initialCheckFuture.complete(null);
                return null;
            });
        }
    }

    private static final class PerEndpointExecutor
            extends AbstractExecutorService implements ScheduledExecutorService {

        private final EventExecutorGroup delegate;
        private final Set<Future<?>> scheduledFutures =
                Collections.newSetFromMap(new NonBlockingIdentityHashMap<>());

        PerEndpointExecutor(EventExecutorGroup delegate) {
            this.delegate = delegate;
        }

        void cancelScheduledFutures() {
            for (final Iterator<Future<?>> i = scheduledFutures.iterator(); i.hasNext();) {
                i.next().cancel(false);
                i.remove();
            }
        }

        @Override
        public void execute(Runnable command) {
            add(delegate.submit(command));
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            return add(delegate.schedule(command, delay, unit));
        }

        @Override
        public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
            return add(delegate.schedule(callable, delay, unit));
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period,
                                                      TimeUnit unit) {
            return add(delegate.scheduleAtFixedRate(command, initialDelay, period, unit));
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay,
                                                         TimeUnit unit) {
            return add(delegate.scheduleWithFixedDelay(command, initialDelay, delay, unit));
        }

        private <T extends Future<U>, U> T add(T future) {
            scheduledFutures.add(future);
            future.addListener(scheduledFutures::remove);
            return future;
        }

        @Override
        public void shutdown() {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Runnable> shutdownNow() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isShutdown() {
            return delegate.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return delegate.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }
    }
}
