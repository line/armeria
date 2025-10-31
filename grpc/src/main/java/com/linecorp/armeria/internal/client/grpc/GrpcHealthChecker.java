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
package com.linecorp.armeria.internal.client.grpc;

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
public final class GrpcHealthChecker extends AbstractGrpcHealthChecker {

    private static final Logger LOGGER = LoggerFactory.getLogger(GrpcHealthChecker.class);

    private final HealthCheckerContext ctx;
    @Nullable
    private final String service;
    private final HealthGrpc.HealthStub stub;

    public GrpcHealthChecker(HealthCheckerContext ctx, Endpoint endpoint, SessionProtocol sessionProtocol,
                             @Nullable String service) {
        this.ctx = ctx;
        this.service = service;

        this.stub = GrpcClients.builder(sessionProtocol, endpoint)
                .options(ctx.clientOptions())
                .build(HealthGrpc.HealthStub.class);
    }

    @Override
    protected void check() {
        lock();
        try {
            final HealthCheckRequest.Builder builder = HealthCheckRequest.newBuilder();
            if (service != null) {
                builder.setService(service);
            }

            try (ClientRequestContextCaptor reqCtxCaptor = Clients.newContextCaptor()) {
                stub.check(builder.build(), new StreamObserver<HealthCheckResponse>() {
                    @Override
                    public void onNext(HealthCheckResponse healthCheckResponse) {
                        final ClientRequestContext reqCtx = reqCtxCaptor.get();
                        if (healthCheckResponse.getStatus() == HealthCheckResponse.ServingStatus.SERVING) {
                            handleHealthyUpdate(reqCtx);
                        } else {
                           handleUnhealthyUpdate(reqCtx, null);
                        }
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        final ClientRequestContext reqCtx = reqCtxCaptor.get();
                        handleUnhealthyUpdate(reqCtx, throwable);
                    }

                    @Override
                    public void onCompleted() {
                    }
                });
            }
        } finally {
            unlock();
        }
    }

    private void handleHealthyUpdate(ClientRequestContext reqCtx) {
        // extract the headers from the ctx log
        ResponseHeaders responseHeaders = null;
        if (reqCtx.log().isAvailable(RequestLogProperty.RESPONSE_HEADERS)) {
            responseHeaders = reqCtx.log().partial().responseHeaders();
        }

        // update health status to healthy
        LOGGER.debug("Health check returned healthy from endpoint {}", ctx.endpoint());
        ctx.updateHealth(HEALTHY, reqCtx, responseHeaders, null);

        // schedule next check
        ctx.executor().schedule(GrpcHealthChecker.this::check,
                ctx.nextDelayMillis(), TimeUnit.MILLISECONDS);
    }

    private void handleUnhealthyUpdate(ClientRequestContext reqCtx, @Nullable Throwable throwable) {
        // extract the headers from the ctx log
        ResponseHeaders responseHeaders = null;
        if (reqCtx.log().isAvailable(RequestLogProperty.RESPONSE_HEADERS)) {
            responseHeaders = reqCtx.log().partial().responseHeaders();
        }

        // update health status to unhealthy
        if (throwable == null) {
            LOGGER.debug("Health check returned unhealthy from endpoint {}", ctx.endpoint());
        } else {
            LOGGER.debug("Failed health check on endpoint {}", ctx.endpoint(), throwable);
        }
        ctx.updateHealth(UNHEALTHY, reqCtx, responseHeaders, throwable);

        // execute next check immediately
        ctx.executor().execute(GrpcHealthChecker.this::check);
    }
}
