/*
 * Copyright 2021 LINE Corporation
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

package com.linecorp.armeria.server.grpc;

import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.healthcheck.HealthCheckUpdateListener;
import com.linecorp.armeria.server.healthcheck.ListenableHealthChecker;

/**
 * Builds a {@link GrpcHealthCheckService}.
 */
public final class GrpcHealthCheckServiceBuilder {

    private final ImmutableSet.Builder<ListenableHealthChecker> healthCheckers = ImmutableSet.builder();

    private final ImmutableMap.Builder<String, ListenableHealthChecker> grpcHealthCheckers =
            ImmutableMap.builder();

    private final ImmutableList.Builder<HealthCheckUpdateListener> updateListeners = ImmutableList.builder();

    GrpcHealthCheckServiceBuilder() {}

    /**
     * Adds the specified {@link ListenableHealthChecker}s that determine the healthiness of the {@link Server}.
     */
    public GrpcHealthCheckServiceBuilder checkers(ListenableHealthChecker... healthCheckers) {
        return checkers(ImmutableSet.copyOf(requireNonNull(healthCheckers, "healthCheckers")));
    }

    /**
     * Adds the specified {@link ListenableHealthChecker}s that determine the healthiness of the {@link Server}.
     */
    public GrpcHealthCheckServiceBuilder checkers(Iterable<? extends ListenableHealthChecker> healthCheckers) {
        this.healthCheckers.addAll(requireNonNull(healthCheckers, "healthCheckers"));
        return this;
    }

    /**
     * Adds the pair of the service name and the {@link ListenableHealthChecker} that determine
     * the healthiness of the specified gRPC service.
     * The specified health checker is not used for determining the healthiness of the {@link Server}.
     *
     * <p>Note: The suggested format of service name is `package_names.ServiceName`.
     * For example, If the proto package that defines `HelloService` belongs to `com.example`,
     * the service name is `com.example.HelloService`
     */
    public GrpcHealthCheckServiceBuilder checkerForGrpcService(String serviceName,
                                                               ListenableHealthChecker healthChecker) {
        grpcHealthCheckers.put(requireNonNull(serviceName, "serviceName"),
                               requireNonNull(healthChecker, "healthChecker"));
        return this;
    }

    /**
     * Adds a {@link HealthCheckUpdateListener} which is invoked when the healthiness of the {@link Server} is
     * updated.
     */
    public GrpcHealthCheckServiceBuilder updateListener(HealthCheckUpdateListener updateListener) {
        updateListeners.add(requireNonNull(updateListener, "updateListener"));
        return this;
    }

    /**
     * Returns a newly created {@link GrpcHealthCheckService} built from the properties specified so far.
     */
    public GrpcHealthCheckService build() {
        return new GrpcHealthCheckService(
                healthCheckers.build(),
                grpcHealthCheckers.build(),
                updateListeners.build()
        );
    }
}
