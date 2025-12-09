package com.linecorp.armeria.client.metric;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;

import java.util.EnumMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;

public class ClientRequestMetrics implements ClientRequestLifecycleListener {

    private final ConcurrentMap<Endpoint, LongAdder> activeRequestsPerEndpoint =
            new ConcurrentHashMap<>();

    private final EnumMap<SessionProtocol, LongAdder> pendingRequestsPerProtocol =
            new EnumMap<>(SessionProtocol.class);

    public ClientRequestMetrics() {
        for (SessionProtocol protocol : SessionProtocol.values()) {
            pendingRequestsPerProtocol.put(protocol, new LongAdder());
        }
    }
    
    @Override
    public void onRequestPending(ClientRequestContext ctx) {
        pendingRequestsPerProtocol.get(ctx.sessionProtocol()).increment();
    }

    @Override
    public void onRequestStart(ClientRequestContext ctx) {
        pendingRequestsPerProtocol.get(ctx.sessionProtocol()).decrement();
        final Endpoint endpoint = ctx.endpoint();
        if (endpoint != null) {
            activeRequestsPerEndpoint
                    .computeIfAbsent(endpoint, e -> new LongAdder())
                    .increment();    
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
        final Endpoint endpoint = ctx.endpoint();
        if (endpoint != null) {
            final LongAdder adder = activeRequestsPerEndpoint.get(endpoint);
            if (adder != null) {
                adder.decrement();
            }
        }
    }
    
    public long activeRequestsPerEndpoint(Endpoint endpoint) {
        final LongAdder longAdder = activeRequestsPerEndpoint.get(endpoint);
        return longAdder != null ? longAdder.sum() : 0L;
    }

    public long activeRequests() {
        long sum = 0L;
        for (LongAdder adder : activeRequestsPerEndpoint.values()) {
            sum += adder.sum(); 
        }
        return sum;
    }
    
    public long pendingRequestsPerProtocol(SessionProtocol protocol) {
        final LongAdder longAdder = pendingRequestsPerProtocol.get(protocol);
        return longAdder != null ? longAdder.sum() : 0L;
    }
    
    public long pendingRequest() {
        long sum = 0L;
        for (LongAdder adder : pendingRequestsPerProtocol.values()) {
            sum += adder.sum();
        }
        return sum;
    }
    
}
