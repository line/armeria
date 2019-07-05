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
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Streams;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.util.ReleasableHolder;

import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

final class DefaultEventLoopScheduler implements EventLoopScheduler {

    private static final AtomicLongFieldUpdater<DefaultEventLoopScheduler> lastCleanupTimeNanosUpdater =
            AtomicLongFieldUpdater.newUpdater(DefaultEventLoopScheduler.class, "lastCleanupTimeNanos");

    private static final AtomicIntegerFieldUpdater<DefaultEventLoopScheduler> acquisitionStartIndexUpdater =
            AtomicIntegerFieldUpdater.newUpdater(DefaultEventLoopScheduler.class, "acquisitionStartIndex");

    private static final long CLEANUP_INTERVAL_NANOS = Duration.ofMinutes(1).toNanos();

    private static final int DEFAULT_MAX_NUM_EVENT_LOOPS = 1;

    private static final int NO_PORT = 0;

    private final List<EventLoop> eventLoops;

    private final int maxNumEventLoopsPerEndpoint;
    private final int maxNumEventLoopsPerHttp1Endpoint;

    private final Map<StateKey, EventLoopState> states = new ConcurrentHashMap<>();

    private final Map<String, PredefinedEndpointState> predefinedGroupStates = new HashMap<>();
    private final Map<String, Int2ObjectMap<PredefinedEndpointState>> predefinedEndpointStates =
            new HashMap<>();

    private int cleaupCounter;

    @SuppressWarnings("FieldMayBeFinal")
    private volatile int acquisitionStartIndex;

    @SuppressWarnings("FieldMayBeFinal")
    private volatile long lastCleanupTimeNanos = System.nanoTime();

    DefaultEventLoopScheduler(EventLoopGroup eventLoopGroup, int maxNumEventLoopsPerEndpoint,
                              int maxNumEventLoopsPerHttp1Endpoint,
                              Map<Endpoint, Integer> maxNumEventLoopsMap) {
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

        for (Map.Entry<Endpoint, Integer> entry : maxNumEventLoopsMap.entrySet()) {
            final int maxNumEventLoops = Math.min(entry.getValue(), eventLoopSize);
            final Endpoint endpoint = entry.getKey();
            if (endpoint.isGroup()) {
                predefinedGroupStates.computeIfAbsent(
                        endpoint.groupName(),
                        unused -> new PredefinedEndpointState(eventLoops, maxNumEventLoops, this));
                continue;
            }

            final int port = endpoint.hasPort() ? endpoint.port() : NO_PORT;
            final String host = endpoint.host();
            putMaxNumEventLoops(host, port, maxNumEventLoops);

            if (endpoint.hasIpAddr() && !endpoint.isIpAddrOnly()) {
                putMaxNumEventLoops(endpoint.ipAddr(), port, maxNumEventLoops);
            }
        }
    }

    private void putMaxNumEventLoops(String host, int port, int maxNumEventLoops) {
        final Int2ObjectMap<PredefinedEndpointState> portToMaxNum =
                predefinedEndpointStates.computeIfAbsent(host, unused -> new Int2ObjectOpenHashMap<>());
        portToMaxNum.putIfAbsent(port, new PredefinedEndpointState(eventLoops, maxNumEventLoops, this));
    }

    /**
     * Returns the index of {@link #eventLoops} which an {@link EventLoopState} will use to acquire an
     * event loop from. This also sets the index for the next {@link EventLoopState} which calls this
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
        requireNonNull(sessionProtocol, "sessionProtocol");
        final EventLoopState state = state(endpoint, sessionProtocol);
        final EventLoopEntry acquired = state.acquire();
        cleanup();
        return acquired;
    }

    @VisibleForTesting
    List<EventLoopEntry> entries(Endpoint endpoint, SessionProtocol sessionProtocol) {
        return state(endpoint, sessionProtocol).entries();
    }

    private EventLoopState state(Endpoint endpoint, SessionProtocol sessionProtocol) {
        if (endpoint.isGroup()) {
            return groupState(endpoint, sessionProtocol);
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

        if (!predefinedEndpointStates.isEmpty()) {
            EventLoopState state = predefinedEndpointState(firstTryHost, port);
            if (state != null) {
                return state;
            }

            if (secondTryHost != null) {
                state = predefinedEndpointState(secondTryHost, port);
                if (state != null) {
                    return state;
                }
            }
        }

        final boolean isHttp1 = isHttp1(sessionProtocol);
        final StateKey firstKey = new StateKey(firstTryHost, port, isHttp1);
        EventLoopState state = states.get(firstKey);
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

        return state(firstKey, sessionProtocol);
    }

    private EventLoopState state(StateKey stateKey, SessionProtocol sessionProtocol) {
        return states.computeIfAbsent(
                stateKey, unused -> EventLoopState.of(eventLoops, maxNumEventLoops(sessionProtocol), this));
    }

    private EventLoopState groupState(Endpoint endpoint, SessionProtocol sessionProtocol) {
        final String groupName = endpoint.groupName();
        final PredefinedEndpointState predefinedGroup = predefinedGroupStates.get(groupName);
        if (predefinedGroup != null) {
            return predefinedGroup.getOrCreate();
        }

        return state(new StateKey(groupName, sessionProtocol.defaultPort(), isHttp1(sessionProtocol)),
                     sessionProtocol);
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

    @Nullable
    private EventLoopState predefinedEndpointState(String host, int port) {
        final Int2ObjectMap<PredefinedEndpointState> predefinedEndpointMap = predefinedEndpointStates.get(host);
        if (predefinedEndpointMap != null) {
            PredefinedEndpointState predefinedEndpointState = predefinedEndpointMap.get(port);
            if (predefinedEndpointState != null) {
                return predefinedEndpointState.getOrCreate();
            }

            predefinedEndpointState = predefinedEndpointMap.get(NO_PORT);
            if (predefinedEndpointState != null) {
                return predefinedEndpointState.getOrCreate();
            }
        }
        return null;
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

        for (final Iterator<EventLoopState> i = states.values().iterator(); i.hasNext();) {
            final EventLoopState state = i.next();
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

    private static final class PredefinedEndpointState {

        private static final AtomicReferenceFieldUpdater<PredefinedEndpointState, EventLoopState> stateUpdater =
                AtomicReferenceFieldUpdater.newUpdater(PredefinedEndpointState.class,
                                                       EventLoopState.class, "state");

        private final List<EventLoop> eventLoops;
        private final int maxNumEventLoops;
        private final DefaultEventLoopScheduler scheduler;
        @Nullable
        private volatile EventLoopState state;

        PredefinedEndpointState(List<EventLoop> eventLoops, int maxNumEventLoops,
                                DefaultEventLoopScheduler scheduler) {
            this.eventLoops = eventLoops;
            this.maxNumEventLoops = maxNumEventLoops;
            this.scheduler = scheduler;
        }

        EventLoopState getOrCreate() {
            EventLoopState state = this.state;
            if (state == null) {
                state = EventLoopState.of(eventLoops, maxNumEventLoops, scheduler);
                if (!stateUpdater.compareAndSet(this, null, state)) {
                    state = this.state;
                    assert state != null;
                }
            }
            return state;
        }
    }

    private static final class StateKey {
        private final String hostOrGroupName;
        private final int port;
        private final boolean isHttp1;

        StateKey(String hostOrGroupName, int port, boolean isHttp1) {
            this.hostOrGroupName = hostOrGroupName;
            this.port = port;
            this.isHttp1 = isHttp1;
        }

        @Override
        public int hashCode() {
            return (hostOrGroupName.hashCode() * 31 + port) * 31 + Boolean.hashCode(isHttp1);
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
            return hostOrGroupName.equals(that.hostOrGroupName) && port == that.port && isHttp1 == that.isHttp1;
        }
    }
}
