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
 * Performs gRPC health checking using the Check rpc endpoint.
 */
final class GrpcHealthChecker extends AbstractGrpcHealthChecker {

    private static final Logger LOGGER = LoggerFactory.getLogger(GrpcHealthChecker.class);

    private final HealthCheckerContext ctx;
    @Nullable
    private final String service;
    private final HealthGrpc.HealthStub stub;
    @Nullable
    private ClientRequestContext activeRequestContext;

    GrpcHealthChecker(HealthCheckerContext ctx, Endpoint endpoint, SessionProtocol sessionProtocol,
                      @Nullable String service) {
        this.ctx = requireNonNull(ctx, "ctx");
        requireNonNull(endpoint, "endpoint");
        requireNonNull(sessionProtocol, "sessionProtocol");
        this.service = service;

        this.stub = GrpcClients.builder(sessionProtocol, endpoint)
                .options(ctx.clientOptions())
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
                stub.check(builder.build(), new CheckObserver(this, reqCtxCaptor));
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

    private void updateHealth(double health, ClientRequestContext reqCtx, @Nullable Throwable throwable) {
        lock();
        try {
            if (isClosed()) {
                return;
            }

            // extract the headers from the ctx log
            ResponseHeaders responseHeaders = null;
            if (reqCtx.log().isAvailable(RequestLogProperty.RESPONSE_HEADERS)) {
                responseHeaders = reqCtx.log().partial().responseHeaders();
            }

            if (throwable != null) {
                logCheckFailure(LOGGER, ctx.endpoint(), throwable);
            } else if (health == HEALTHY) {
                LOGGER.trace("Health check returned healthy from endpoint {}", ctx.endpoint());
            } else {
                LOGGER.trace("Health check returned unhealthy from endpoint {}", ctx.endpoint());
            }
            ctx.updateHealth(health, reqCtx, responseHeaders, throwable);
        } finally {
            unlock();
        }
    }

    private void scheduleNextCheck() {
        lock();
        try {
            if (isClosed()) {
                return;
            }

            // schedule next check using the retry backoff, to avoid tight-looping against
            // an unhealthy or unavailable server
            ctx.executor().schedule(GrpcHealthChecker.this::check,
                                    ctx.nextDelayMillis(), TimeUnit.MILLISECONDS);
        } finally {
            unlock();
        }
    }

    /**
     * A {@link StreamObserver} for the unary {@code Check} rpc. Since gRPC doesn't guarantee that
     * {@link #onNext(HealthCheckResponse)} precedes a terminal event in every implementation, health is
     * only reported once the RPC terminates: if a message was received, it determines the reported health
     * regardless of how the stream was closed; otherwise the endpoint is reported unhealthy.
     */
    private static final class CheckObserver implements StreamObserver<HealthCheckResponse> {

        private final GrpcHealthChecker checker;
        private final ClientRequestContextCaptor reqCtxCaptor;
        private HealthCheckResponse.ServingStatus servingStatus = HealthCheckResponse.ServingStatus.UNKNOWN;

        CheckObserver(GrpcHealthChecker checker, ClientRequestContextCaptor reqCtxCaptor) {
            this.checker = checker;
            this.reqCtxCaptor = reqCtxCaptor;
        }

        @Override
        public void onNext(HealthCheckResponse healthCheckResponse) {
            servingStatus = healthCheckResponse.getStatus();
        }

        @Override
        public void onError(Throwable throwable) {
            final ClientRequestContext reqCtx = reqCtxCaptor.get();
            checker.updateHealth(toHealth(servingStatus), reqCtx, throwable);
            checker.scheduleNextCheck();
        }

        @Override
        public void onCompleted() {
            final ClientRequestContext reqCtx = reqCtxCaptor.get();
            checker.updateHealth(toHealth(servingStatus), reqCtx, null);
            checker.scheduleNextCheck();
        }

        private static double toHealth(HealthCheckResponse.ServingStatus servingStatus) {
            return servingStatus == HealthCheckResponse.ServingStatus.SERVING ? HEALTHY : UNHEALTHY;
        }
    }
}
