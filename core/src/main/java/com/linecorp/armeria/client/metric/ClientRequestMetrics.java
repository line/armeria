/*
 * Copyright 2025 LY Corporation
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

package com.linecorp.armeria.client.metric;

import java.util.EnumMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.RequestLogListener;

import io.netty.util.AttributeKey;

/**
 * A {@link ClientRequestLifecycleListener} that collects the number of pending and active requests.
 *
 * <p>This class tracks:
 * <ul>
 * <li><b>Pending requests:</b> Requests that have been created but have not yet started execution
 * (e.g., waiting for a connection). Tracked per {@link SessionProtocol}.</li>
 * <li><b>Active requests:</b> Requests that have started execution (headers sent) but have not yet
 * completed. Tracked per {@link Endpoint}.</li>
 * </ul>
 */
public class ClientRequestMetrics implements ClientRequestLifecycleListener {

    private static final AttributeKey<State> METRICS_STATE =
            AttributeKey.valueOf(ClientRequestMetrics.class, "METRICS_STATE");

    private final ConcurrentMap<Endpoint, LongAdder> activeRequestsPerEndpoint =
            new ConcurrentHashMap<>();

    private final EnumMap<SessionProtocol, LongAdder> pendingRequestsPerProtocol =
            new EnumMap<>(SessionProtocol.class);

    private final RequestLogListener requestLogListener = (property, log) -> {
        if (!(log.context() instanceof ClientRequestContext)) {
            return;
        }

        final ClientRequestContext ctx = (ClientRequestContext) log.context();
        switch (property) {
            case REQUEST_FIRST_BYTES_TRANSFERRED_TIME:
                onRequestStart(ctx);
                break;
            case ALL_COMPLETE:
                onRequestComplete(ctx, log.responseCause());
                break;
            default:
                break;
        }
    };

    /**
     * Creates a new {@link ClientRequestMetrics} instance.
     */
    public ClientRequestMetrics() {
        for (SessionProtocol protocol : SessionProtocol.values()) {
            pendingRequestsPerProtocol.put(protocol, new LongAdder());
        }
    }

    @Override
    public void onRequestPending(ClientRequestContext ctx) {
        final State s = state(ctx);
        if (s.pendingCounted.compareAndSet(false, true)) {
            final LongAdder longAdder = pendingRequestsPerProtocol.get(ctx.sessionProtocol());
            if (longAdder != null) {
                longAdder.increment();
            }
        }
    }

    @Override
    public void onRequestStart(ClientRequestContext ctx) {
        final State s = state(ctx);
        if (s.pendingCounted.getAndSet(false)) {
            final LongAdder longAdder = pendingRequestsPerProtocol.get(ctx.sessionProtocol());
            if (longAdder != null) {
                longAdder.decrement();
            }
        }

        if (s.activeCounted.compareAndSet(false, true)) {
            final Endpoint endpoint = ctx.endpoint();
            if (endpoint != null) {
                activeRequestsPerEndpoint
                        .computeIfAbsent(endpoint, e -> new LongAdder())
                        .increment();
            }
        }
    }

    @Override
    public void onRequestSendComplete(ClientRequestContext ctx) {
        // no-op
    }

    @Override
    public void onResponseHeaders(ClientRequestContext ctx, ResponseHeaders headers) {
        // no-op
    }

    @Override
    public void onResponseComplete(ClientRequestContext ctx) {
        // no-op
    }

    @Override
    public void onRequestComplete(ClientRequestContext ctx, @Nullable Throwable cause) {
        final State s = state(ctx);

        if (s.pendingCounted.getAndSet(false)) {
            final LongAdder longAdder = pendingRequestsPerProtocol.get(ctx.sessionProtocol());
            if (longAdder != null) {
                longAdder.decrement();
            }
        }

        if (!s.activeCounted.getAndSet(false)) {
            return;
        }

        final Endpoint endpoint = ctx.endpoint();
        if (endpoint != null) {
            final LongAdder adder = activeRequestsPerEndpoint.get(endpoint);
            if (adder != null) {
                adder.decrement();
            }
        }
    }

    @Override
    public RequestLogListener asRequestLogListener() {
        return this.requestLogListener;
    }

    /**
     * Returns the number of active requests for the specified {@link Endpoint}.
     * An active request is one that has started sending data but has not yet completed.
     */
    public long activeRequestsPerEndpoint(Endpoint endpoint) {
        final LongAdder longAdder = activeRequestsPerEndpoint.get(endpoint);
        return longAdder != null ? longAdder.sum() : 0L;
    }

    /**
     * Returns the total number of active requests across all endpoints.
     */
    public long activeRequests() {
        long sum = 0L;
        for (LongAdder adder : activeRequestsPerEndpoint.values()) {
            sum += adder.sum();
        }
        return sum;
    }

    /**
     * Returns the number of pending requests for the specified {@link SessionProtocol}.
     * A pending request is one that is scheduled but has not yet acquired a connection or started sending.
     */
    public long pendingRequestsPerProtocol(SessionProtocol protocol) {
        final LongAdder longAdder = pendingRequestsPerProtocol.get(protocol);
        return longAdder != null ? longAdder.sum() : 0L;
    }

    /**
     * Returns the total number of pending requests across all protocols.
     */
    public long pendingRequest() {
        long sum = 0L;
        for (LongAdder adder : pendingRequestsPerProtocol.values()) {
            sum += adder.sum();
        }
        return sum;
    }

    private static State state(ClientRequestContext ctx) {
        State s = ctx.ownAttr(METRICS_STATE);
        if (s != null) {
            return s;
        }

        s = new State();
        ctx.setAttr(METRICS_STATE, s);
        return s;
    }

    private static final class State {
        final AtomicBoolean pendingCounted = new AtomicBoolean(false);
        final AtomicBoolean activeCounted = new AtomicBoolean(false);
    }
}
