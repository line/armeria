/*
 * Copyright 2019 LINE Corporation
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

package com.linecorp.armeria.client;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.function.ToIntFunction;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Streams;

import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.util.ReleasableHolder;

import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;

final class DefaultEventLoopScheduler implements EventLoopScheduler {
    private static final Logger logger = LoggerFactory.getLogger(DefaultEventLoopScheduler.class);

    private static final AtomicLongFieldUpdater<DefaultEventLoopScheduler> lastCleanupTimeNanosUpdater =
            AtomicLongFieldUpdater.newUpdater(DefaultEventLoopScheduler.class, "lastCleanupTimeNanos");

    private static final AtomicIntegerFieldUpdater<DefaultEventLoopScheduler> acquisitionStartIndexUpdater =
            AtomicIntegerFieldUpdater.newUpdater(DefaultEventLoopScheduler.class, "acquisitionStartIndex");

    private static final long CLEANUP_INTERVAL_NANOS = Duration.ofMinutes(1).toNanos();

    static final int DEFAULT_MAX_NUM_EVENT_LOOPS = 1;

    private final List<EventLoop> eventLoops;

    private final int maxNumEventLoopsPerEndpoint;
    private final int maxNumEventLoopsPerHttp1Endpoint;

    private final Map<StateKey, AbstractEventLoopState> states = new ConcurrentHashMap<>();

    private final List<ToIntFunction<Endpoint>> maxNumEventLoopsFunctions;

    private int cleanupCounter;

    @SuppressWarnings("FieldMayBeFinal")
    private volatile int acquisitionStartIndex;

    @SuppressWarnings("FieldMayBeFinal")
    private volatile long lastCleanupTimeNanos = System.nanoTime();

    DefaultEventLoopScheduler(EventLoopGroup eventLoopGroup, int maxNumEventLoopsPerEndpoint,
                              int maxNumEventLoopsPerHttp1Endpoint,
                              List<ToIntFunction<Endpoint>> maxNumEventLoopsFunctions) {
        eventLoops = Streams.stream(eventLoopGroup)
                            .map(EventLoop.class::cast)
                            .collect(toImmutableList());
        final int eventLoopSize = eventLoops.size();
        acquisitionStartIndex = ThreadLocalRandom.current().nextInt(eventLoopSize);

        if (maxNumEventLoopsPerEndpoint <= 0) {
            this.maxNumEventLoopsPerEndpoint = DEFAULT_MAX_NUM_EVENT_LOOPS;
        } else {
            this.maxNumEventLoopsPerEndpoint = Math.min(maxNumEventLoopsPerEndpoint, eventLoopSize);
        }

        if (maxNumEventLoopsPerHttp1Endpoint <= 0) {
            this.maxNumEventLoopsPerHttp1Endpoint = this.maxNumEventLoopsPerEndpoint;
        } else {
            this.maxNumEventLoopsPerHttp1Endpoint =
                    Math.min(maxNumEventLoopsPerHttp1Endpoint, eventLoopSize);
        }
        this.maxNumEventLoopsFunctions = ImmutableList.copyOf(maxNumEventLoopsFunctions);
    }

    /**
     * Returns the index of {@link #eventLoops} which an {@link AbstractEventLoopState} will use to acquire an
     * event loop from. This also sets the index for the next {@link AbstractEventLoopState} which calls this
     * method.
     */
    int acquisitionStartIndex(int need) {
        for (;;) {
            final int current = acquisitionStartIndex;
            final int next = (current + need) % eventLoops.size();
            if (acquisitionStartIndexUpdater.compareAndSet(this, current, next)) {
                return current;
            }
        }
    }

    @Override
    public ReleasableHolder<EventLoop> acquire(SessionProtocol sessionProtocol,
                                               EndpointGroup endpointGroup,
                                               @Nullable Endpoint endpoint) {
        requireNonNull(sessionProtocol, "sessionProtocol");
        requireNonNull(endpointGroup, "endpointGroup");
        final AbstractEventLoopState state = state(sessionProtocol, endpointGroup, endpoint);
        final AbstractEventLoopEntry acquired = state.acquire();
        cleanup();
        return acquired;
    }

    @VisibleForTesting
    List<AbstractEventLoopEntry> entries(SessionProtocol sessionProtocol,
                                         EndpointGroup endpointGroup,
                                         @Nullable Endpoint endpoint) {
        return state(sessionProtocol, endpointGroup, endpoint).entries();
    }

    /**
     * Returns the {@link AbstractEventLoopState} in the map whose key is {@link StateKey}. If the IP address
     * exists in the specified {@link Endpoint}, it's used in the key to find the
     * {@link AbstractEventLoopState} first. Then {@link Endpoint#host()} is used.
     * If {@link AbstractEventLoopState} does not exist in the map, the state is created with the maximum
     * number of {@link EventLoop}s. The number can be produced by the {@code maxNumEventLoopsFunction} or
     * {@code maxNumEventLoopsPerEndpoint} and {@code maxNumEventLoopsPerHttp1Endpoint} dependent on
     * the {@link SessionProtocol} when the {@code maxNumEventLoopsFunction} does not produce a value.
     */
    private AbstractEventLoopState state(SessionProtocol sessionProtocol,
                                         EndpointGroup endpointGroup,
                                         @Nullable Endpoint endpoint) {
        if (endpoint == null) {
            // Use a fake endpoint if no endpoint was selected from the endpointGroup.
            endpoint = Endpoint.of(
                    "armeria-group-" + Integer.toHexString(System.identityHashCode(endpointGroup)));
        }

        final String firstTryHost;
        final String secondTryHost;
        if (endpoint.hasIpAddr()) {
            final String ipAddr = endpoint.ipAddr();
            assert ipAddr != null;
            firstTryHost = ipAddr;
            if (endpoint.isIpAddrOnly()) {
                secondTryHost = null;
            } else {
                secondTryHost = endpoint.host();
            }
        } else {
            firstTryHost = endpoint.host();
            secondTryHost = null;
        }

        final int port = endpoint.hasPort() ? endpoint.port() : sessionProtocol.defaultPort();
        final Endpoint endpointWithPort = endpoint.withPort(port);
        final boolean isHttp1 = isHttp1(sessionProtocol, endpointWithPort);
        final StateKey firstKey = new StateKey(firstTryHost, port, isHttp1);
        AbstractEventLoopState state = states.get(firstKey);
        if (state != null) {
            return state;
        }

        if (secondTryHost != null) {
            final StateKey secondKey = new StateKey(secondTryHost, port, isHttp1);
            state = states.get(secondKey);
            if (state != null) {
                return state;
            }
        }

        // Try with the endpoint which has a port first.
        int maxNumEventLoopsCandidate = maxNumEventLoopsCandidate(endpointWithPort);
        if (maxNumEventLoopsCandidate <= 0 && !endpointWithPort.equals(endpoint)) {
            // Try without the port second.
            maxNumEventLoopsCandidate = maxNumEventLoopsCandidate(endpoint);
        }

        final int maxNumEventLoops =
                maxNumEventLoopsCandidate > 0 ? Math.min(maxNumEventLoopsCandidate, eventLoops.size())
                                              : maxNumEventLoops(sessionProtocol, endpointWithPort);
        return states.computeIfAbsent(firstKey,
                                      unused -> AbstractEventLoopState.of(eventLoops, maxNumEventLoops, this));
    }

    private int maxNumEventLoopsCandidate(Endpoint endpoint) {
        for (ToIntFunction<Endpoint> function : maxNumEventLoopsFunctions) {
            final int maxNumEventLoopsCandidate = function.applyAsInt(endpoint);
            if (maxNumEventLoopsCandidate > 0) {
                logger.debug("maxNumEventLoops: {}, for the endpoint: {}", maxNumEventLoopsCandidate, endpoint);
                return maxNumEventLoopsCandidate;
            }
        }
        return 0;
    }

    private int maxNumEventLoops(SessionProtocol sessionProtocol, Endpoint endpointWithPort) {
        return isHttp1(sessionProtocol, endpointWithPort) ? maxNumEventLoopsPerHttp1Endpoint
                                                          : maxNumEventLoopsPerEndpoint;
    }

    private static boolean isHttp1(SessionProtocol sessionProtocol, Endpoint endpointWithPort) {
        if (sessionProtocol == SessionProtocol.H1C || sessionProtocol == SessionProtocol.H1) {
            return true;
        }

        if (sessionProtocol == SessionProtocol.HTTP) {
            return SessionProtocolNegotiationCache.isUnsupported(endpointWithPort, SessionProtocol.H2C);
        }

        if (sessionProtocol == SessionProtocol.HTTPS) {
            return SessionProtocolNegotiationCache.isUnsupported(endpointWithPort, SessionProtocol.H2);
        }

        return false;
    }

    /**
     * Cleans up empty entries with no activity for more than 1 minute. For reduced overhead, we perform this
     * only when 1) the last clean-up was more than 1 minute ago and 2) the number of acquisitions % 256 is 0.
     */
    private void cleanup() {
        if ((++cleanupCounter & 0xFF) != 0) { // (++counter % 256) != 0
            return;
        }

        final long currentTimeNanos = System.nanoTime();
        final long lastCleanupTimeNanos = this.lastCleanupTimeNanos;
        if (currentTimeNanos - lastCleanupTimeNanos < CLEANUP_INTERVAL_NANOS ||
            !lastCleanupTimeNanosUpdater.compareAndSet(this, lastCleanupTimeNanos, currentTimeNanos)) {
            return;
        }

        for (final Iterator<AbstractEventLoopState> i = states.values().iterator(); i.hasNext();) {
            final AbstractEventLoopState state = i.next();
            final boolean remove;

            synchronized (state) {
                remove = state.allActiveRequests() == 0 &&
                         currentTimeNanos - state.lastActivityTimeNanos() >= CLEANUP_INTERVAL_NANOS;
            }

            if (remove) {
                i.remove();
            }
        }
    }

    private static final class StateKey {
        private final String ipOrHost;
        private final int port;
        private final boolean isHttp1;

        StateKey(String ipOrHost, int port, boolean isHttp1) {
            this.ipOrHost = ipOrHost;
            this.port = port;
            this.isHttp1 = isHttp1;
        }

        @Override
        public int hashCode() {
            return (ipOrHost.hashCode() * 31 + port) * 31 + Boolean.hashCode(isHttp1);
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (this == obj) {
                return true;
            }

            if (!(obj instanceof StateKey)) {
                return false;
            }

            final StateKey that = (StateKey) obj;
            return ipOrHost.equals(that.ipOrHost) && port == that.port && isHttp1 == that.isHttp1;
        }
    }
}
