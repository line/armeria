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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.function.ToIntFunction;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Streams;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.util.ReleasableHolder;

import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;

final class DefaultEventLoopScheduler implements EventLoopScheduler {

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

    private final ToIntFunction<Endpoint> maxNumEventLoopsFunction;

    private int cleaupCounter;

    @SuppressWarnings("FieldMayBeFinal")
    private volatile int acquisitionStartIndex;

    @SuppressWarnings("FieldMayBeFinal")
    private volatile long lastCleanupTimeNanos = System.nanoTime();

    DefaultEventLoopScheduler(EventLoopGroup eventLoopGroup, int maxNumEventLoopsPerEndpoint,
                              int maxNumEventLoopsPerHttp1Endpoint,
                              ToIntFunction<Endpoint> maxNumEventLoopsFunction) {
        eventLoops = Streams.stream(eventLoopGroup)
                            .map(EventLoop.class::cast)
                            .collect(toImmutableList());
        final int eventLoopSize = eventLoops.size();
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
        this.maxNumEventLoopsFunction = maxNumEventLoopsFunction;
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
    public ReleasableHolder<EventLoop> acquire(Endpoint endpoint, SessionProtocol sessionProtocol) {
        requireNonNull(endpoint, "endpoint");
        checkArgument(!endpoint.isGroup(), "endpoint must be a host: %s", endpoint);
        requireNonNull(sessionProtocol, "sessionProtocol");
        final AbstractEventLoopState state = state(endpoint, sessionProtocol);
        final AbstractEventLoopEntry acquired = state.acquire();
        cleanup();
        return acquired;
    }

    @VisibleForTesting
    List<AbstractEventLoopEntry> entries(Endpoint endpoint, SessionProtocol sessionProtocol) {
        return state(endpoint, sessionProtocol).entries();
    }

    private AbstractEventLoopState state(Endpoint endpoint, SessionProtocol sessionProtocol) {
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
        final boolean isHttp1 = isHttp1(sessionProtocol);
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

        int maxNumEventLoopsCandidate = maxNumEventLoopsFunction.applyAsInt(endpoint.withPort(port));
        if (maxNumEventLoopsCandidate <= 0 && !endpoint.hasPort()) {
            maxNumEventLoopsCandidate = maxNumEventLoopsFunction.applyAsInt(endpoint);
        }

        final int maxNumEventLoops =
                maxNumEventLoopsCandidate > 0 ? Math.min(maxNumEventLoopsCandidate, eventLoops.size())
                                              : maxNumEventLoops(sessionProtocol);
        return states.computeIfAbsent(firstKey,
                                      unused -> AbstractEventLoopState.of(eventLoops, maxNumEventLoops, this));
    }

    private int maxNumEventLoops(SessionProtocol sessionProtocol) {
        return isHttp1(sessionProtocol) ? maxNumEventLoopsPerHttp1Endpoint
                                        : maxNumEventLoopsPerEndpoint;
    }

    // TODO(minwoox) Use SessionProtocolNegotiationCache and enpoint to bring the protocol version when the
    //               SessionProtocol is HTTP and HTTPS.
    private static boolean isHttp1(SessionProtocol sessionProtocol) {
        return sessionProtocol == SessionProtocol.H1C || sessionProtocol == SessionProtocol.H1;
    }

    /**
     * Cleans up empty entries with no activity for more than 1 minute. For reduced overhead, we perform this
     * only when 1) the last clean-up was more than 1 minute ago and 2) the number of acquisitions % 256 is 0.
     */
    private void cleanup() {
        if ((++cleaupCounter & 0xFF) != 0) { // (++counter % 256) != 0
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
        public boolean equals(Object obj) {
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
