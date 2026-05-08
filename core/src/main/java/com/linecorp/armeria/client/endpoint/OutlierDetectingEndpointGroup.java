/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.client.endpoint;

import static com.linecorp.armeria.client.endpoint.DynamicEndpointGroup.UNINITIALIZED_ENDPOINTS;
import static com.linecorp.armeria.internal.client.endpoint.EndpointToStringUtil.toShortString;
import static com.linecorp.armeria.internal.common.util.CollectionUtil.truncate;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.math.LongMath;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.DecoratingHttpClientFunction;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreaker;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerListener;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerListenerAdapter;
import com.linecorp.armeria.client.circuitbreaker.CircuitState;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.SuccessFunction;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.logging.RequestLogAccess;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.common.util.AbstractListenable;
import com.linecorp.armeria.common.util.AsyncCloseableSupport;
import com.linecorp.armeria.common.util.ListenableAsyncCloseable;
import com.linecorp.armeria.internal.client.circuitbreaker.CircuitBreakerConfig;
import com.linecorp.armeria.internal.common.util.ReentrantShortLock;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * An {@link EndpointGroup} that detects bad endpoints and rotates them out using a per-endpoint
 * {@link CircuitBreaker}. It periodically refreshes the endpoints and selects an endpoint based on the
 * {@link CircuitBreaker} state.
 *
 * <ul>
 *   <li>Keep-Alive: When the underlying {@link EndpointGroup} (such as DNS-based ones) churns its
 *       {@link Endpoint}s frequently, pooled connections become unusable and have to be re-established,
 *       hurting performance and response duration. This {@link EndpointGroup} keeps the cached endpoints
 *       for {@code maxEndpointAge}. If an {@link Endpoint} exceeds its lifespan, it is automatically
 *       removed from the valid {@link Endpoint}s and a new {@link Endpoint} is added.</li>
 *   <li>Circuit breaker: This {@link EndpointGroup} uses {@link CircuitBreaker} to avoid sending requests to
 *       bad {@link Endpoint}s. Each response is classified by the configured {@link SuccessFunction} (by
 *       default, status codes 2xx-4xx are successful and everything else is a failure), and the per-endpoint
 *       {@link CircuitBreaker} updates its counts accordingly. If the failure rate exceeds the
 *       {@linkplain OutlierDetectingEndpointGroupBuilder#failureRateThreshold(double) configured threshold},
 *       the {@link Endpoint} is removed from the valid {@link Endpoint}s and a new {@link Endpoint} is
 *       added automatically.</li>
 * </ul>
 *
 * <h2>Registering the HTTP decorator</h2>
 *
 * <p><strong>The {@link DecoratingHttpClientFunction} returned by {@link #asDecorator()} MUST be
 * registered on the client.</strong> The decorator is what observes each response and feeds success/failure
 * signals into the per-endpoint {@link CircuitBreaker}. Without it, the circuit breakers never trip, bad
 * endpoints are never evicted, and this {@link EndpointGroup} effectively degrades to a periodic refresher.
 *
 * <p>Example:
 * <pre>{@code
 * OutlierDetectingEndpointGroup endpointGroup =
 *         OutlierDetectingEndpointGroup.builder(delegate)
 *                                      .maxNumEndpoints(20)
 *                                      .maxEndpointAge(Duration.ofMinutes(5))
 *                                      .failureRateThreshold(0.3)
 *                                      .circuitOpenWindow(Duration.ofSeconds(20))
 *                                      .meterRegistry(meterIdPrefix, meterRegistry)
 *                                      .build();
 *
 * WebClient client =
 *         WebClient.builder(SessionProtocol.HTTPS, endpointGroup)
 *                  // Required: report each response to the per-endpoint CircuitBreaker.
 *                  .decorator(endpointGroup.asDecorator())
 *                  .build();
 * }</pre>
 */
@UnstableApi
public final class OutlierDetectingEndpointGroup implements EndpointGroup, ListenableAsyncCloseable {

    // Forked from https://github.com/line/pushsphere/blob/main/client/src/main/kotlin/com/linecorp/pushsphere/client/OutlierDetectingEndpointGroup.kt
    // and migrated to Java and adapted for Armeria.

    private static final Logger logger = LoggerFactory.getLogger(OutlierDetectingEndpointGroup.class);

    /**
     * JVM-wide counter shared by every {@link OutlierDetectingEndpointGroup} instance to mint
     * unique per-endpoint {@link CircuitBreaker} names. The counter is monotonic across instances,
     * so circuit breaker numbers within a single group are not contiguous.
     */
    private static final AtomicLong circuitBreakerCounter = new AtomicLong();

    /**
     * Returns a newly created {@link OutlierDetectingEndpointGroupBuilder} that wraps the given
     * {@link EndpointGroup}.
     *
     * @param delegate the underlying {@link EndpointGroup} whose endpoints are filtered through
     *                 per-endpoint {@link CircuitBreaker}s.
     */
    public static OutlierDetectingEndpointGroupBuilder builder(EndpointGroup delegate) {
        return new OutlierDetectingEndpointGroupBuilder(delegate);
    }

    /**
     * An executor that schedules endpoint refresh and bad-endpoint cleanup tasks.
     */
    private final ScheduledExecutorService executor = CommonPools.workerGroup().next();

    private final EndpointGroup delegate;
    private final int maxNumEndpoints;
    private final String namePrefix;
    private final boolean failFastOnAllCircuitOpen;
    private final SuccessFunction successFunction;
    private final CircuitBreakerConfig circuitBreakerConfig;

    private final ReentrantShortLock endpointsLock = new ReentrantShortLock();

    // Guarded by `endpointsLock`.
    private volatile List<Endpoint> endpoints = UNINITIALIZED_ENDPOINTS;

    private final OutlierEndpointGroupListener endpointGroupListeners;
    private final CompletableFuture<List<Endpoint>> initialCompletionFuture = new CompletableFuture<>();

    private final Map<Endpoint, EndpointContext> endpointContexts = new ConcurrentHashMap<>();
    private final EndpointSelectionStrategy selectionStrategy;
    private final EndpointSelector endpointSelector;

    private final long maxEndpointAgeNanoTime;
    private final long badEndpointExpirationMillis;

    private final Set<Endpoint> badEndpoints = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private final CircuitBreakerListener cbListener = new CircuitBreakerListenerAdapter() {
        @Override
        public void onStateChanged(String circuitBreakerName, CircuitState state) {
            handleCircuitBreakerStateChanged(circuitBreakerName);
        }
    };

    private final AsyncCloseableSupport closeable = AsyncCloseableSupport.of(this::doCloseAsync);

    OutlierDetectingEndpointGroup(EndpointGroup delegate,
                                  int maxNumEndpoints,
                                  long maxEndpointAgeMillis,
                                  String namePrefix,
                                  boolean failFastOnAllCircuitOpen,
                                  SuccessFunction successFunction,
                                  CircuitBreakerConfig circuitBreakerConfig,
                                  @Nullable MeterIdPrefix meterIdPrefix,
                                  @Nullable MeterRegistry meterRegistry) {
        this.delegate = delegate;
        this.maxNumEndpoints = maxNumEndpoints;
        this.namePrefix = namePrefix;
        this.failFastOnAllCircuitOpen = failFastOnAllCircuitOpen;
        this.successFunction = successFunction;
        this.circuitBreakerConfig = circuitBreakerConfig;

        endpointGroupListeners = new OutlierEndpointGroupListener(this::endpoints);
        selectionStrategy = new CircuitBreakerEndpointSelectionStrategy();
        endpointSelector = selectionStrategy.newSelector(this);

        maxEndpointAgeNanoTime = maxEndpointAgeMillis > 0
                                 ? TimeUnit.MILLISECONDS.toNanos(maxEndpointAgeMillis)
                                 : -1;   // Sentinel: age-based rotation disabled.
        badEndpointExpirationMillis = circuitBreakerConfig.circuitOpenWindow().toMillis();

        delegate.addListener(unused -> {
            endpointsLock.lock();
            try {
                // Strict mode (age disabled): always refresh so the pool mirrors the delegate.
                // Keep-alive mode (age enabled): only refresh when there are slots to fill — cached
                // endpoints stay in the pool until they age out.
                if (maxEndpointAgeNanoTime <= 0 || endpointContexts.size() < maxNumEndpoints) {
                    refreshEndpoints(false);
                }
            } finally {
                endpointsLock.unlock();
            }
        });

        delegate.whenReady().handle((unused, cause) -> {
            if (cause != null) {
                logger.warn("Failed to initialize the delegate: {}", delegate, cause);
                initialCompletionFuture.completeExceptionally(cause);
                return null;
            }

            if (maxEndpointAgeNanoTime > 0) {
                scheduleEndpointUpdateTask();
            } else {
                // Age-based rotation is disabled. Do an initial population only.
                refreshEndpoints(false);
            }
            return null;
        });

        if (meterIdPrefix != null && meterRegistry != null) {
            final String name = meterIdPrefix.name("endpoints.count");
            Gauge.builder(name, this, obj -> obj.endpoints().size())
                 .tags(meterIdPrefix.tags())
                 .tag("state", "healthy")
                 .description("The number of healthy endpoints")
                 .register(meterRegistry);

            Gauge.builder(name, badEndpoints, Set::size)
                 .tags(meterIdPrefix.tags())
                 .tag("state", "unhealthy")
                 .description("The number of unhealthy endpoints")
                 .register(meterRegistry);
        }
    }

    /**
     * Schedules a task to update the old endpoints periodically.
     */
    private void scheduleEndpointUpdateTask() {
        if (closeable.isClosing()) {
            return;
        }

        final long nextDurationMillis = refreshEndpoints(true);
        executor.schedule(this::scheduleEndpointUpdateTask, nextDurationMillis, TimeUnit.MILLISECONDS);
    }

    private long newExpirationNanoTime(long currentNanoTime) {
        if (maxEndpointAgeNanoTime <= 0) {
            // Age-based rotation disabled. Endpoints never age out.
            return Long.MAX_VALUE;
        }

        // Use 20% of max age as jitter to avoid thundering herd problem.
        final long jitter = Math.max(maxEndpointAgeNanoTime / 5, 1);
        return LongMath.saturatedAdd(LongMath.saturatedAdd(currentNanoTime, maxEndpointAgeNanoTime),
                                     ThreadLocalRandom.current().nextLong(jitter));
    }

    @VisibleForTesting
    @Nullable
    EndpointContext endpointContext(Endpoint endpoint) {
        return endpointContexts.get(endpoint);
    }

    /**
     * Updates {@code endpoints} if the sorted input differs from the current value, then offloads
     * listener notification (and the initial-readiness future) to {@link #executor} so the callbacks
     * never run while {@code endpointsLock} is held by the calling thread. Sorting stabilizes the
     * round-robin sequence since {@code endpointContexts} is unordered.
     */
    private void setEndpoints(Iterable<Endpoint> endpoints) {
        final List<Endpoint> newEndpoints = ImmutableList.sortedCopyOf(endpoints);
        if (!DynamicEndpointGroup.hasChanges(this.endpoints, newEndpoints)) {
            return;
        }
        this.endpoints = newEndpoints;
        logger.info("New endpoints have been set: {}", toShortString(newEndpoints));
        executor.execute(() -> {
            if (closeable.isClosing()) {
                return;
            }

            if (!initialCompletionFuture.isDone()) {
                initialCompletionFuture.complete(newEndpoints);
            }
            endpointGroupListeners.notifyListeners0(newEndpoints);
        });
    }

    private CircuitBreaker newCircuitBreaker(Endpoint endpoint) {
        final String name = namePrefix + '-' +
                            circuitBreakerCounter.incrementAndGet() + ':' + endpoint;
        return CircuitBreaker.builder(name)
                             .failureRateThreshold(circuitBreakerConfig.failureRateThreshold())
                             .minimumRequestThreshold(circuitBreakerConfig.minimumRequestThreshold())
                             .trialRequestInterval(circuitBreakerConfig.trialRequestInterval())
                             .circuitOpenWindow(circuitBreakerConfig.circuitOpenWindow())
                             .counterSlidingWindow(circuitBreakerConfig.counterSlidingWindow())
                             .counterUpdateInterval(circuitBreakerConfig.counterUpdateInterval())
                             .listener(cbListener)
                             .build();
    }

    /**
     * Invoked when the {@link CircuitState} of a per-endpoint {@link CircuitBreaker} changes.
     * If the circuit breaker belongs to one of the tracked endpoints, the rotation is refreshed
     * to evict the now-bad endpoint.
     */
    private void handleCircuitBreakerStateChanged(String circuitBreakerName) {
        endpointsLock.lock();
        try {
            boolean needsUpdate = false;
            for (EndpointContext context : endpointContexts.values()) {
                if (context.circuitBreaker().name().equals(circuitBreakerName)) {
                    needsUpdate = true;
                    break;
                }
            }
            if (needsUpdate) {
                refreshEndpoints(false);
            }
        } finally {
            endpointsLock.unlock();
        }
    }

    /**
     * Evicts open-circuit endpoints, drops aged-out ones, and fills the remaining slots with fresh
     * candidates from the delegate (reusing still-valid old endpoints if needed).
     *
     * @param isScheduledJob {@code true} from the periodic scheduler, or {@code false} when triggered
     *                       reactively (delegate update or circuit state change).
     * @return the next refresh delay in ms when scheduled (100 when empty, 500 on errors, otherwise
     *         clamped to ≥500 ms). Returns {@code 0} when not a scheduled job.
     */
    private long refreshEndpoints(boolean isScheduledJob) {
        if (closeable.isClosing()) {
            return 0;
        }

        endpointsLock.lock();
        try {
            // Remove bad endpoints.
            final ImmutableList.Builder<Endpoint> newBadEndpointsBuilder = ImmutableList.builder();
            for (Entry<Endpoint, EndpointContext> entry : endpointContexts.entrySet()) {
                if (entry.getValue().circuitBreaker().circuitState() != CircuitState.CLOSED) {
                    newBadEndpointsBuilder.add(entry.getKey());
                }
            }
            final List<Endpoint> newBadEndpoints = newBadEndpointsBuilder.build();

            if (!newBadEndpoints.isEmpty()) {
                logger.info("Evicting endpoints from rotation due to open circuits: {}",
                            toShortString(newBadEndpoints));
                for (Endpoint badEndpoint : newBadEndpoints) {
                    badEndpoints.add(badEndpoint);
                    endpointContexts.remove(badEndpoint);
                }

                // Schedule a task to remove the bad endpoint from the badEndpoints.
                executor.schedule(() -> {
                    if (closeable.isClosing()) {
                        return;
                    }
                    newBadEndpoints.forEach(badEndpoints::remove);
                    if (logger.isDebugEnabled()) {
                        logger.debug("Bad endpoints are eligible to rejoin the rotation: {}",
                                     toShortString(newBadEndpoints));
                    }
                    // Bad endpoints are removed. Make the endpoint available if there are not enough endpoints.
                    refreshEndpoints(false);
                }, badEndpointExpirationMillis, TimeUnit.MILLISECONDS);
            }

            // Remove old endpoints (only meaningful in keep-alive mode — endpoints never age out
            // when maxEndpointAgeNanoTime is disabled).
            final long currentNanoTime = System.nanoTime();
            final ImmutableSet<Endpoint> oldEndpoints;
            if (maxEndpointAgeNanoTime > 0) {
                final ImmutableSet.Builder<Endpoint> oldEndpointsBuilder = ImmutableSet.builder();
                for (Entry<Endpoint, EndpointContext> entry : endpointContexts.entrySet()) {
                    if (currentNanoTime - entry.getValue().expirationNanoTime() >= 0) {
                        oldEndpointsBuilder.add(entry.getKey());
                    }
                }
                oldEndpoints = oldEndpointsBuilder.build();
            } else {
                oldEndpoints = ImmutableSet.of();
            }

            // Capture old contexts so they can be reused if there aren't enough new candidates.
            final List<EndpointContext> oldEndpointContexts;
            if (!oldEndpoints.isEmpty()) {
                final ImmutableList.Builder<EndpointContext> oldContextsBuilder = ImmutableList.builder();
                for (Endpoint oldEndpoint : oldEndpoints) {
                    final EndpointContext context = endpointContexts.remove(oldEndpoint);
                    if (context != null) {
                        oldContextsBuilder.add(context);
                    }
                }
                oldEndpointContexts = oldContextsBuilder.build();
            } else {
                oldEndpointContexts = ImmutableList.of();
            }

            // Fetch new endpoints.
            final List<Endpoint> candidates = delegate.endpoints();

            // When age-based rotation is disabled, mirror the delegate strictly.
            if (maxEndpointAgeNanoTime <= 0) {
                final Set<Endpoint> candidateSet = ImmutableSet.copyOf(candidates);
                endpointContexts.keySet().removeIf(e -> !candidateSet.contains(e));
            }

            int remainingSlots = maxNumEndpoints - endpointContexts.size();
            for (Endpoint candidate : candidates) {
                if (remainingSlots <= 0) {
                    break;
                }
                // Exclude the existing endpoints.
                if (endpointContexts.containsKey(candidate)) {
                    continue;
                }
                // Exclude the old endpoints.
                if (oldEndpoints.contains(candidate)) {
                    continue;
                }
                // Exclude the bad endpoints.
                if (badEndpoints.contains(candidate)) {
                    continue;
                }
                final long expirationNanoTime = newExpirationNanoTime(currentNanoTime);
                endpointContexts.put(candidate,
                                     new EndpointContext(candidate, newCircuitBreaker(candidate),
                                                         expirationNanoTime));
                remainingSlots--;
            }

            // Fill remaining slots by reusing old endpoints (capped so the pool never exceeds the max).
            if (remainingSlots > 0) {
                for (EndpointContext context : oldEndpointContexts) {
                    if (remainingSlots <= 0) {
                        break;
                    }
                    if (!candidates.contains(context.endpoint())) {
                        continue;
                    }
                    final long expirationNanoTime = newExpirationNanoTime(currentNanoTime);
                    // Extend the expiration. The CircuitBreaker's accumulated state is preserved.
                    endpointContexts.put(context.endpoint(),
                                         new EndpointContext(context.endpoint(), context.circuitBreaker(),
                                                             expirationNanoTime));
                    remainingSlots--;
                }
            }

            setEndpoints(endpointContexts.keySet());

            if (!isScheduledJob) {
                return 0;
            }

            // Compute the next update interval.
            long minRemainingNanoTime = Long.MAX_VALUE;
            boolean hasContext = false;
            for (EndpointContext context : endpointContexts.values()) {
                hasContext = true;
                final long remaining = context.expirationNanoTime() - currentNanoTime;
                if (remaining < minRemainingNanoTime) {
                    minRemainingNanoTime = remaining;
                }
            }
            if (!hasContext) {
                // No endpoints. Retry after 100 ms to quickly fetch the next endpoints.
                return 100;
            }
            // Clamp the min interval to 500 ms to avoid too frequent updates.
            return Math.max(TimeUnit.NANOSECONDS.toMillis(minRemainingNanoTime), 500);
        } catch (Throwable e) {
            logger.error("Unexpected exception while updating endpoints.", e);
            return 500;
        } finally {
            endpointsLock.unlock();
        }
    }

    @Nullable
    @Override
    public Endpoint selectNow(ClientRequestContext ctx) {
        return endpointSelector.selectNow(ctx);
    }

    @Deprecated
    @Override
    public CompletableFuture<Endpoint> select(ClientRequestContext ctx,
                                              ScheduledExecutorService executor,
                                              long timeoutMillis) {
        return select(ctx, executor);
    }

    @Override
    public CompletableFuture<Endpoint> select(ClientRequestContext ctx, ScheduledExecutorService executor) {
        return endpointSelector.select(ctx, executor);
    }

    /**
     * Returns a {@link DecoratingHttpClientFunction} that reports each HTTP response to the per-endpoint
     * {@link CircuitBreaker}.
     *
     * <p><strong>Must be registered on the HTTP client</strong> via
     * {@code WebClientBuilder.decorator(...)}. Without it, circuit breakers never trip and outlier
     * detection becomes a no-op periodic refresher.
     */
    public DecoratingHttpClientFunction asDecorator() {
        return (delegate, ctx, req) -> {
            reportResult(ctx);
            return delegate.execute(ctx, req);
        };
    }

    /**
     * Reports the result of the request to the {@link CircuitBreaker}, classifying the response via the
     * configured {@link SuccessFunction}.
     */
    private void reportResult(ClientRequestContext ctx) {
        final Endpoint endpoint = ctx.endpoint();
        if (endpoint == null) {
            // Nothing to report.
            return;
        }
        final EndpointContext endpointContext = endpointContexts.get(endpoint);
        if (endpointContext == null) {
            return;
        }
        final CircuitBreaker circuitBreaker = endpointContext.circuitBreaker();
        ctx.log().whenComplete().thenAccept(log -> {
            if (successFunction.isSuccess(ctx, log)) {
                circuitBreaker.onSuccess();
            } else {
                circuitBreaker.onFailure();
            }
        });
    }

    @Override
    public void close() {
        closeable.close();
    }

    @Override
    public CompletableFuture<?> closeAsync() {
        return closeable.closeAsync();
    }

    @Override
    public boolean isClosing() {
        return closeable.isClosing();
    }

    @Override
    public boolean isClosed() {
        return closeable.isClosed();
    }

    @Override
    public CompletableFuture<?> whenClosed() {
        return closeable.whenClosed();
    }

    private void doCloseAsync(CompletableFuture<?> future) {
        if (!initialCompletionFuture.isDone()) {
            initialCompletionFuture.cancel(false);
        }
        delegate.closeAsync().handle((unused1, unused2) -> future.complete(null));
    }

    @Override
    public List<Endpoint> endpoints() {
        return endpoints;
    }

    @Override
    public EndpointSelectionStrategy selectionStrategy() {
        return selectionStrategy;
    }

    @Override
    public long selectionTimeoutMillis() {
        return delegate.selectionTimeoutMillis();
    }

    @Override
    public CompletableFuture<List<Endpoint>> whenReady() {
        return initialCompletionFuture;
    }

    @Override
    public void addListener(Consumer<? super List<Endpoint>> listener, boolean notifyLatestEndpoints) {
        endpointGroupListeners.addListener(listener, notifyLatestEndpoints);
    }

    @Override
    public void removeListener(Consumer<?> listener) {
        endpointGroupListeners.removeListener(listener);
    }

    @Override
    public String toString() {
        final List<Endpoint> endpoints = this.endpoints;
        return MoreObjects.toStringHelper(this)
                          .add("delegate", delegate)
                          .add("maxNumEndpoints", maxNumEndpoints)
                          .add("maxEndpointAge",
                               maxEndpointAgeNanoTime > 0
                               ? TimeUnit.NANOSECONDS.toMillis(maxEndpointAgeNanoTime) + "ms"
                               : "disabled")
                          .add("namePrefix", namePrefix)
                          .add("failFastOnAllCircuitOpen", failFastOnAllCircuitOpen)
                          .add("initialized", initialCompletionFuture.isDone())
                          .add("closing", closeable.isClosing())
                          .add("numEndpoints", endpoints.size())
                          .add("numBadEndpoints", badEndpoints.size())
                          .add("endpoints", truncate(endpoints, 10))
                          .toString();
    }

    private static final class OutlierEndpointGroupListener extends AbstractListenable<List<Endpoint>> {

        private final Supplier<List<Endpoint>> latest;

        OutlierEndpointGroupListener(Supplier<List<Endpoint>> latest) {
            this.latest = latest;
        }

        @Nullable
        @Override
        protected List<Endpoint> latestValue() {
            final List<Endpoint> latest0 = latest.get();
            return latest0 == UNINITIALIZED_ENDPOINTS ? null : latest0;
        }

        void notifyListeners0(List<Endpoint> latestValue) {
            notifyListeners(latestValue);
        }
    }

    private final class CircuitBreakerEndpointSelectionStrategy implements EndpointSelectionStrategy {
        @Override
        public EndpointSelector newSelector(EndpointGroup endpointGroup) {
            final EndpointSelector selector = delegate.selectionStrategy().newSelector(endpointGroup);
            return new CircuitBreakerEndpointSelector(selector);
        }
    }

    private final class CircuitBreakerEndpointSelector implements EndpointSelector {

        private final EndpointSelector selector;

        CircuitBreakerEndpointSelector(EndpointSelector selector) {
            this.selector = selector;
        }

        @Nullable
        @Override
        public Endpoint selectNow(ClientRequestContext ctx) {
            Endpoint endpoint = selector.selectNow(ctx);
            if (endpoint == null) {
                // No endpoint is available.
                return null;
            }
            final RequestLogAccess parent = ctx.log().parent();
            if (parent == null || parent.children().isEmpty()) {
                // No retry or the first attempt.
                return endpoint;
            }

            for (int i = 0; i < 3; i++) {
                RequestLogAccess duplicated = null;
                for (RequestLogAccess child : parent.children()) {
                    final ClientRequestContext cctx = (ClientRequestContext) child.context();
                    if (Objects.equals(cctx.endpoint(), endpoint)) {
                        duplicated = child;
                        break;
                    }
                }
                if (duplicated == null) {
                    return endpoint;
                }
                // The endpoint was used in the previous attempt. Try another endpoint.
                endpoint = selector.selectNow(ctx);
            }

            // All endpoints are used to send the previous attempts. Use the endpoint anyway.
            return endpoint;
        }

        @Deprecated
        @Override
        public CompletableFuture<Endpoint> select(ClientRequestContext ctx,
                                                  ScheduledExecutorService executor,
                                                  long timeoutMillis) {
            // Deprecated overload. Delegated as-is to the wrapped selector.
            return selector.select(ctx, executor, timeoutMillis);
        }

        @Override
        public CompletableFuture<Endpoint> select(ClientRequestContext ctx,
                                                  ScheduledExecutorService executor) {
            final int numBadEndpoints = badEndpoints.size();
            if (endpoints.isEmpty() && numBadEndpoints > 0 && !failFastOnAllCircuitOpen) {
                // All endpoints are bad — likely a local/network issue. Fall back to a bad endpoint
                // rather than failing until the underlying group refreshes.
                final CompletableFuture<Endpoint> fallback = selectNowFromBadEndpoints(numBadEndpoints);
                if (fallback != null) {
                    return fallback;
                }
            }
            return selector.select(ctx, executor);
        }

        @Nullable
        private CompletableFuture<Endpoint> selectNowFromBadEndpoints(int numBadEndpoints) {
            int target = ThreadLocalRandom.current().nextInt(numBadEndpoints);
            Endpoint badEndpoint = null;
            for (Endpoint endpoint : badEndpoints) {
                if (target-- == 0) {
                    badEndpoint = endpoint;
                    break;
                }
            }
            if (badEndpoint != null) {
                return CompletableFuture.completedFuture(badEndpoint);
            }
            // Concurrently emptied. Let the caller fall back to the regular selector.
            return null;
        }
    }

    static final class EndpointContext {

        private final Endpoint endpoint;
        private final CircuitBreaker circuitBreaker;
        private final long expirationNanoTime;

        EndpointContext(Endpoint endpoint, CircuitBreaker circuitBreaker, long expirationNanoTime) {
            this.endpoint = endpoint;
            this.circuitBreaker = circuitBreaker;
            this.expirationNanoTime = expirationNanoTime;
        }

        Endpoint endpoint() {
            return endpoint;
        }

        CircuitBreaker circuitBreaker() {
            return circuitBreaker;
        }

        long expirationNanoTime() {
            return expirationNanoTime;
        }
    }
}
