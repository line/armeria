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

import java.util.function.Function;

import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.endpoint.healthcheck.AbstractHealthCheckedEndpointGroupBuilder;
import com.linecorp.armeria.client.endpoint.healthcheck.HealthCheckerContext;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.AsyncCloseable;
import com.linecorp.armeria.internal.client.grpc.GrpcHealthCheckWatcher;
import com.linecorp.armeria.internal.client.grpc.GrpcHealthChecker;

/**
 * Builds a health checked endpoint group whose health comes from a standard gRPC health check service.
 */
public final class GrpcHealthCheckedEndpointGroupBuilder
        extends AbstractHealthCheckedEndpointGroupBuilder<GrpcHealthCheckedEndpointGroupBuilder> {

    private @Nullable String service;
    private final GrpcHealthCheckMethod healthCheckMethod;

    GrpcHealthCheckedEndpointGroupBuilder(EndpointGroup delegate, GrpcHealthCheckMethod healthCheckMethod) {
        super(delegate);
        this.healthCheckMethod = healthCheckMethod;
    }

    /**
     * Returns a {@link GrpcHealthCheckedEndpointGroupBuilder} that builds a health checked
     * endpoint group with the specified {@link EndpointGroup} and {@link GrpcHealthCheckMethod}.
     */
    public static GrpcHealthCheckedEndpointGroupBuilder builder(EndpointGroup delegate,
                                                                GrpcHealthCheckMethod healthCheckMethod) {
        return new GrpcHealthCheckedEndpointGroupBuilder(requireNonNull(delegate),
                requireNonNull(healthCheckMethod));
    }

    /**
     * Sets the optional service field of the gRPC health check request.
     */
    public GrpcHealthCheckedEndpointGroupBuilder service(@Nullable String service) {
        this.service = service;
        return this;
    }

    @Override
    protected Function<? super HealthCheckerContext, ? extends AsyncCloseable> newCheckerFactory() {
        return new GrpcHealthCheckerFactory(service, healthCheckMethod);
    }

    private static final class GrpcHealthCheckerFactory
            implements Function<HealthCheckerContext, AsyncCloseable> {

        private final @Nullable String service;
        private final GrpcHealthCheckMethod healthCheckMethod;

        private GrpcHealthCheckerFactory(@Nullable String service, GrpcHealthCheckMethod healthCheckMethod) {
            this.service = service;
            this.healthCheckMethod = healthCheckMethod;
        }

        @Override
        public AsyncCloseable apply(HealthCheckerContext ctx) {
            if (healthCheckMethod == GrpcHealthCheckMethod.CHECK) {
                final GrpcHealthChecker healthChecker = new GrpcHealthChecker(ctx, ctx.endpoint(),
                        ctx.protocol(), service);
                healthChecker.start();
                return healthChecker;
            } else if (healthCheckMethod == GrpcHealthCheckMethod.WATCH) {
                final GrpcHealthCheckWatcher healthChecker = new GrpcHealthCheckWatcher(ctx, ctx.endpoint(),
                        ctx.protocol(), service);
                healthChecker.start();
                return healthChecker;
            }
            // should not get here
            throw new IllegalArgumentException("Invalid health check method");
        }
    }
}
