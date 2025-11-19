/*
 * Copyright 2025 LINE Corporation
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

package com.linecorp.armeria.client.metric;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;

/**
 * Collects simple client-side metrics such as:
 * <ul>
 *     <li>The number of pending requests per {@link SessionProtocol}</li>
 *     <li>The number of active requests per {@link Endpoint}</li>
 * </ul>
 * This class is intended to be used as an in-memory counter.
 */
public class ClientMetrics {

    private static final Logger logger = LoggerFactory.getLogger(ClientMetrics.class);

    private final LongAdder httpsPendingRequest;
    private final LongAdder httpPendingRequest;
    private final LongAdder http1PendingRequest;
    private final LongAdder http2PendingRequest;
    private final LongAdder proxyPendingRequest;

    // Endpoint does not override equals() and hashCode().
    // Call sites must use the same 'Endpoint' instance when invoking
    // 'incrementActiveRequest(...)' and 'decrementActiveRequest'.
    private final ConcurrentMap<Endpoint, LongAdder> activeRequestsPerEndpoint;

    /**
     * Creates a new instance with all counters initialized to zero.
     */
    public ClientMetrics() {
        this.activeRequestsPerEndpoint = new ConcurrentHashMap<>();
        this.httpsPendingRequest = new LongAdder();
        this.httpPendingRequest = new LongAdder();
        this.http1PendingRequest = new LongAdder();
        this.http2PendingRequest = new LongAdder();
        this.proxyPendingRequest = new LongAdder();
    }

    /**
     * Increments the number of active requests for the
     * specified {@link Endpoint}.
     * @param endpoint the endpoint.
     */
    public void incrementActiveRequest(@Nullable Endpoint endpoint) {
        if (endpoint == null) {
            return;
        }

        this.activeRequestsPerEndpoint
                .computeIfAbsent(endpoint, unusedKey -> new LongAdder())
                .increment();
    }

    /**
     * Decrements the number of active requests for the
     * specified {@link Endpoint}. If the counter for the
     * {@code endpoint} becomes zero after decrement,
     * the entry is removed from {@code activeRequestsPerEndpoint}.
     * @param endpoint the endpoint.
     */
    public void decrementActiveRequest(@Nullable Endpoint endpoint) {
        if (endpoint == null) {
            return;
        }

        activeRequestsPerEndpoint.computeIfPresent(endpoint, (key, counter) -> {
            counter.decrement();
            final long currentCount = counter.sum();
            assert currentCount >= 0;
            return currentCount == 0 ? null : counter;
        });
    }

    /**
     * Decrements the number of pending requests for the given {@link SessionProtocol}.
     * @param desiredProtocol the desired protocol.
     */
    public void decrementPendingRequest(SessionProtocol desiredProtocol) {
        final LongAdder counter = counter(desiredProtocol);
        if (counter != null) {
            counter.decrement();
            assert counter.sum() >= 0;
        }
    }

    /**
     * Increments the number of pending requests for the given {@link SessionProtocol}.
     * @param desiredProtocol the desired protocol.
     */
    public void incrementPendingRequest(SessionProtocol desiredProtocol) {
        final LongAdder counter = counter(desiredProtocol);
        if (counter != null) {
            counter.increment();
        }
    }

    /**
     * Returns the total number of pending requests across all supported protocols.
     * @return the total number of pending requests
     */
    public long pendingRequests() {
        return httpPendingRequests() +
                httpsPendingRequests() +
                http1PendingRequests() +
                http2PendingRequests() +
                proxyPendingRequests();
    }

    /**
     * Returns the count of http pending requests.
     * @return the count of http pending requests.
     */
    public long httpPendingRequests() {
        return httpPendingRequest.sum();
    }

    /**
     * Returns the count of https pending requests.
     * @return the count of https pending requests.
     */
    public long httpsPendingRequests() {
        return httpsPendingRequest.sum();
    }

    /**
     * Returns the count of http1 pending requests.
     * @return the count of http1 pending requests.
     */
    public long http1PendingRequests() {
        return http1PendingRequest.sum();
    }

    /**
     * Returns the count of http2 pending requests.
     * @return the count of http2 pending requests.
     */
    public long http2PendingRequests() {
        return http2PendingRequest.sum();
    }

    /**
     * Returns the count of proxy pending requests.
     * @return the count of proxy pending requests.
     */
    public long proxyPendingRequests() {
        return proxyPendingRequest.sum();
    }

    /**
     * Returns a snapshot of the number of active requests per {@link Endpoint}.
     * @return a map whose key is an {@link Endpoint} and whose value is the number of
     *         active requests currently associated with it
     */
    public Map<Endpoint, Long> activeRequestPerEndpoint() {
        final Map<Endpoint, Long> result = new HashMap<>();
        for (Map.Entry<Endpoint, LongAdder> entry : activeRequestsPerEndpoint.entrySet()) {
            final Endpoint key = entry.getKey();
            final LongAdder value = entry.getValue();
            result.put(key, value.sum());
        }

        return result;
    }

    @Nullable
    private LongAdder counter(SessionProtocol desiredProtocol) {
        switch (desiredProtocol) {
            case HTTPS:
                return httpsPendingRequest;
            case HTTP:
                return httpPendingRequest;
            case H1:
                return http1PendingRequest;
            case H1C:
                return http1PendingRequest;
            case H2:
                return http2PendingRequest;
            case H2C:
                return http2PendingRequest;
            case PROXY:
                return proxyPendingRequest;
            default:
                // To prevent log explosion in production environment.
                logger.debug("Unexpected SessionProtocol: {}", desiredProtocol);
                return null;
        }
    }
}
