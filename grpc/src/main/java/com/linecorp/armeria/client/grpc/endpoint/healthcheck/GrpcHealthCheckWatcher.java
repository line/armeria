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
package com.linecorp.armeria.client.grpc.endpoint.healthcheck;

import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.healthcheck.HealthCheckerContext;
import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.RequestLogProperty;

import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthGrpc;
import io.grpc.stub.StreamObserver;

/**
 * Performs gRPC health checking using the Watch rpc endpoint.
 */
class GrpcHealthCheckWatcher extends AbstractGrpcHealthChecker {

    private static final Logger logger = LoggerFactory.getLogger(GrpcHealthCheckWatcher.class);

    private final HealthCheckerContext ctx;
    @Nullable
    private final String service;
    private final HealthGrpc.HealthStub stub;
    @Nullable
    private ClientRequestContext activeRequestContext;

    GrpcHealthCheckWatcher(HealthCheckerContext ctx, Endpoint endpoint, SessionProtocol sessionProtocol,
                           @Nullable String service) {
        this.ctx = requireNonNull(ctx, "ctx");
        requireNonNull(endpoint, "endpoint");
        requireNonNull(sessionProtocol, "sessionProtocol");
        this.service = service;

        stub = GrpcClients.builder(sessionProtocol, endpoint)
                .options(ctx.clientOptions())
                .responseTimeout(Duration.ZERO) // disable timeout for streaming watch rpc
                .maxResponseLength(0) // disable the total response length limit for the long-lived stream
                .maxResponseMessageLength(3200) // limit the length of each individual message instead
                .build(HealthGrpc.HealthStub.class);
    }

    @Override
    protected void check() {
        lock();
        try {
            if (isClosed()) {
                return;
            }

            final HealthCheckRequest.Builder builder = HealthCheckRequest.newBuilder();
            if (service != null) {
                builder.setService(service);
            }

            try (ClientRequestContextCaptor reqCtxCaptor = Clients.newContextCaptor()) {
                stub.watch(builder.build(), new WatchObserver(this));
                activeRequestContext = reqCtxCaptor.get();
            }
        } finally {
            unlock();
        }
    }

    @Override
    protected void cancelActiveCheck() {
        if (activeRequestContext != null) {
            activeRequestContext.cancel();
            activeRequestContext = null;
        }
    }

    private void updateHealth(double health, ClientRequestContext reqCtx,
                              @Nullable ResponseHeaders responseHeaders, @Nullable Throwable throwable) {
        lock();
        try {
            if (isClosed()) {
                return;
            }

            if (throwable != null) {
                logCheckFailure(logger, ctx.endpoint(), throwable);
            } else if (health == HEALTHY) {
                logger.trace("Health check returned healthy from endpoint {}", ctx.endpoint());
            } else {
                logger.trace("Health check returned unhealthy from endpoint {}", ctx.endpoint());
            }
            ctx.updateHealth(health, reqCtx, responseHeaders, throwable);
        } finally {
            unlock();
        }
    }

    private void scheduleNextCheck(boolean immediate) {
        lock();
        try {
            if (isClosed()) {
                return;
            }

            if (immediate) {
                // The stream delivered at least one message before it closed, so the server was
                // reachable moments ago; reconnect immediately instead of backing off.
                ctx.executor().execute(this::check);
            } else {
                // No message was ever received on this stream attempt; back off before retrying,
                // to avoid tight-looping against an unhealthy or unavailable server.
                ctx.executor().schedule(this::check, ctx.nextDelayMillis(), TimeUnit.MILLISECONDS);
            }
        } finally {
            unlock();
        }
    }

    /**
     * A {@link StreamObserver} for the streaming {@code Watch} rpc. Every received message is reported
     * immediately, since the stream stays open for as long as the endpoint keeps sending updates. When the
     * stream closes, it's reconnected immediately if at least one message had been received (the server was
     * reachable moments ago), or after a backoff delay otherwise.
     */
    private static final class WatchObserver implements StreamObserver<HealthCheckResponse> {

        private final GrpcHealthCheckWatcher checker;
        private boolean receivedMessage;
        @Nullable
        private ResponseHeaders responseHeaders;

        WatchObserver(GrpcHealthCheckWatcher checker) {
            this.checker = checker;
        }

        @Override
        public void onNext(HealthCheckResponse healthCheckResponse) {
            receivedMessage = true;
            final ClientRequestContext reqCtx = ClientRequestContext.current();
            final double health = healthCheckResponse.getStatus() ==
                    HealthCheckResponse.ServingStatus.SERVING ? HEALTHY : UNHEALTHY;
            checker.updateHealth(health, reqCtx, responseHeaders(reqCtx), null);
        }

        @Override
        public void onError(Throwable throwable) {
            final ClientRequestContext reqCtx = ClientRequestContext.current();
            checker.updateHealth(UNHEALTHY, reqCtx, responseHeaders(reqCtx), throwable);
            checker.scheduleNextCheck(receivedMessage);
        }

        @Override
        public void onCompleted() {
            final ClientRequestContext reqCtx = ClientRequestContext.current();
            checker.updateHealth(UNHEALTHY, reqCtx, responseHeaders(reqCtx), null);
            checker.scheduleNextCheck(receivedMessage);
        }

        /**
         * Returns the {@link ResponseHeaders} for this watch request, fetching and caching them from the
         * {@link ClientRequestContext} log on first use - there's only one {@link ResponseHeaders} for the
         * life of the stream, so there's no need to look it up again on every message.
         */
        @Nullable
        private ResponseHeaders responseHeaders(ClientRequestContext reqCtx) {
            if (responseHeaders == null && reqCtx.log().isAvailable(RequestLogProperty.RESPONSE_HEADERS)) {
                responseHeaders = reqCtx.log().partial().responseHeaders();
            }
            return responseHeaders;
        }
    }
}
