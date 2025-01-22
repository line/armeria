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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantLock;

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.healthcheck.HealthCheckerContext;
import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.AsyncCloseable;
import com.linecorp.armeria.common.util.AsyncCloseableSupport;
import com.linecorp.armeria.internal.common.util.ReentrantShortLock;

import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthGrpc;
import io.grpc.stub.StreamObserver;

public final class GrpcHealthChecker implements AsyncCloseable {

    static final double HEALTHY = 1d;
    static final double UNHEALTHY = 0d;

    private final HealthCheckerContext ctx;
    @Nullable private final String service;
    private final HealthGrpc.HealthStub stub;

    private final ReentrantLock lock = new ReentrantShortLock();
    private final AsyncCloseableSupport closeable = AsyncCloseableSupport.of(this::closeAsync);

    public GrpcHealthChecker(HealthCheckerContext ctx, Endpoint endpoint, SessionProtocol sessionProtocol,
                             @Nullable String service) {
        this.ctx = ctx;
        this.service = service;

        this.stub = GrpcClients.builder(sessionProtocol, endpoint)
                .options(ctx.clientOptions())
                .build(HealthGrpc.HealthStub.class);
    }

    public void start() {
        check();
    }

    @VisibleForTesting
    void check() {
        lock();
        try {
            final HealthCheckRequest.Builder builder = HealthCheckRequest.newBuilder();
            if (this.service != null) {
                builder.setService(service);
            }

            try (ClientRequestContextCaptor reqCtxCaptor = Clients.newContextCaptor()) {
                stub.check(builder.build(), new StreamObserver<HealthCheckResponse>() {
                    @Override
                    public void onNext(HealthCheckResponse healthCheckResponse) {
                        final ClientRequestContext reqCtx = reqCtxCaptor.get();
                        if (healthCheckResponse.getStatus() == HealthCheckResponse.ServingStatus.SERVING) {
                            ctx.updateHealth(HEALTHY, reqCtx, null, null);
                        } else {
                            ctx.updateHealth(UNHEALTHY, reqCtx, null, null);
                        }
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        final ClientRequestContext reqCtx = reqCtxCaptor.get();
                        ctx.updateHealth(UNHEALTHY, reqCtx, ResponseHeaders.of(500), throwable);
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

    @Override
    public CompletableFuture<?> closeAsync() {
        return closeable.closeAsync();
    }

    private synchronized void closeAsync(CompletableFuture<?> future) {
        future.complete(null);
    }

    @Override
    public void close() {
        closeable.close();
    }

    private void lock() {
        lock.lock();
    }

    private void unlock() {
        lock.unlock();
    }
}
