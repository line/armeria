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

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.jctools.maps.NonBlockingIdentityHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerListenerAdapter;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.healthcheck.HealthCheckUpdateListener;
import com.linecorp.armeria.server.healthcheck.HealthChecker;
import com.linecorp.armeria.server.healthcheck.ListenableHealthChecker;
import com.linecorp.armeria.server.healthcheck.SettableHealthChecker;

import io.grpc.Status;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.health.v1.HealthGrpc.HealthImplBase;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;

/**
 * An implementation of {@code HealthImplBase} that determines a healthiness of a {@link Server}
 * and a healthiness of each gPRC service.
 *
 * <p>This class is implemented based on GRPC Health Checking Protocol.
 * You can set a service name (an empty service name indicates a status of a server) to a request and check
 * the status of the gRPC service from the response by registering it to grpcServiceHealthCheckers.
 * Note: The suggested format of service name is package_names.ServiceName
 * If a server is healthy, returns SERVING.
 * For more details, please refer to the following URL.
 *
 * @see <a href="https://github.com/grpc/grpc/blob/master/doc/health-checking.md">GRPC Health Checking Protocol</a>
 * @see GrpcHealthCheckServiceBuilder
 */
public final class GrpcHealthCheckService extends HealthImplBase {

    private static final Logger logger = LoggerFactory.getLogger(GrpcHealthCheckService.class);

    /**
     * Returns a newly created {@link GrpcHealthCheckService}
     * with the specified {@link ListenableHealthChecker}s.
     */
    public static GrpcHealthCheckService of(ListenableHealthChecker... healthCheckers) {
        return builder().checkers(healthCheckers).build();
    }

    /**
     * Returns a newly created {@link GrpcHealthCheckService}
     * with the specified {@link ListenableHealthChecker}s.
     */
    public static GrpcHealthCheckService of(Iterable<? extends ListenableHealthChecker> healthCheckers) {
        return builder().checkers(healthCheckers).build();
    }

    /**
     * Returns a new builder which builds a new {@link GrpcHealthCheckService}.
     */
    public static GrpcHealthCheckServiceBuilder builder() {
        return new GrpcHealthCheckServiceBuilder();
    }

    private final SettableHealthChecker serverHealth;
    private final Set<ListenableHealthChecker> healthCheckers;
    private final Map<String, ListenableHealthChecker> grpcServiceHealthCheckers;
    private final NonBlockingIdentityHashMap<StreamObserver<HealthCheckResponse>, Entry<String, ServingStatus>>
            watchers = new NonBlockingIdentityHashMap<>();
    @Nullable
    private Server server;

    GrpcHealthCheckService(
            Set<ListenableHealthChecker> healthCheckers,
            Map<String, ListenableHealthChecker> grpcServiceHealthCheckers,
            List<HealthCheckUpdateListener> updateListeners
    ) {
        serverHealth = new SettableHealthChecker(false);
        if (!updateListeners.isEmpty()) {
            addServerHealthUpdateListener(ImmutableList.copyOf(updateListeners));
        }
        this.healthCheckers = ImmutableSet.<ListenableHealthChecker>builder()
                                          .add(serverHealth)
                                          .addAll(healthCheckers)
                                          .build();
        this.grpcServiceHealthCheckers = grpcServiceHealthCheckers;
        setInternalHealthUpdateListener(this.healthCheckers);
        setInternalHealthUpdateListener(this.grpcServiceHealthCheckers.values());
    }

    @Override
    public void check(HealthCheckRequest request, StreamObserver<HealthCheckResponse> responseObserver) {
        final String service = request.getService();
        final ServingStatus status = checkServingStatus(service);
        if (status == ServingStatus.SERVICE_UNKNOWN) {
            responseObserver.onError(Status.NOT_FOUND
                                             .withDescription(String.format(
                                                     "The service name(=%s) is not registered in this service",
                                                     service))
                                             .asRuntimeException());
            return;
        }
        responseObserver.onNext(HealthCheckResponse.newBuilder()
                                                   .setStatus(status)
                                                   .build());
        responseObserver.onCompleted();
    }

    @Override
    public void watch(HealthCheckRequest request,
                      StreamObserver<HealthCheckResponse> responseObserver) {
        final String service = request.getService();
        final HealthCheckResponse response = HealthCheckResponse.newBuilder()
                                                                .setStatus(checkServingStatus(service))
                                                                .build();
        responseObserver.onNext(response);
        watchers.put(responseObserver, Maps.immutableEntry(service, response.getStatus()));
        ((ServerCallStreamObserver<HealthCheckResponse>) responseObserver).setOnCancelHandler(() -> {
            watchers.remove(responseObserver);
        });
    }

    void serviceAdded(ServiceConfig cfg) {
        if (server != null) {
            if (server != cfg.server()) {
                throw new IllegalStateException("Cannot be added to more than one server");
            } else {
                return;
            }
        }

        server = cfg.server();
        server.addListener(new ServerListenerAdapter() {
            @Override
            public void serverStarted(Server server) {
                serverHealth.setHealthy(true);
            }

            @Override
            public void serverStopping(Server server) {
                serverHealth.setHealthy(false);
            }
        });
    }

    @VisibleForTesting
    ServingStatus checkServingStatus(@Nullable String serverName) {
        if (!isHealthy()) {
            return ServingStatus.NOT_SERVING;
        }
        if (Strings.isNullOrEmpty(serverName)) {
            if (grpcServiceHealthCheckers.isEmpty()) {
                return ServingStatus.SERVING;
            }
            if (grpcServiceHealthCheckers.values().stream().allMatch(HealthChecker::isHealthy)) {
                return ServingStatus.SERVING;
            } else {
                return ServingStatus.NOT_SERVING;
            }
        } else {
            if (grpcServiceHealthCheckers.containsKey(serverName)) {
                if (grpcServiceHealthCheckers.get(serverName).isHealthy()) {
                    return ServingStatus.SERVING;
                } else {
                    return ServingStatus.NOT_SERVING;
                }
            } else {
                return ServingStatus.SERVICE_UNKNOWN;
            }
        }
    }

    @VisibleForTesting
    void changeServerStatus(boolean isHealthy) {
        serverHealth.setHealthy(isHealthy);
    }

    private void addServerHealthUpdateListener(ImmutableList<HealthCheckUpdateListener> updateListeners) {
        serverHealth.addListener(healthChecker -> {
            updateListeners.forEach(updateListener -> {
                try {
                    updateListener.healthUpdated(healthChecker.isHealthy());
                } catch (Throwable t) {
                    logger.warn("Unexpected exception from HealthCheckUpdateListener.healthUpdated():", t);
                }
            });
        });
    }

    private void setInternalHealthUpdateListener(Collection<ListenableHealthChecker> listenableHealthCheckers) {
        listenableHealthCheckers.forEach(lhc -> {
            lhc.addListener(healthChecker -> {
                try {
                    provideInternalHealthUpdateListener().healthUpdated(healthChecker.isHealthy());
                } catch (Throwable t) {
                    logger.warn("Unexpected exception from HealthCheckUpdateListener.healthUpdated():", t);
                }
            });
        });
    }

    private HealthCheckUpdateListener provideInternalHealthUpdateListener() {
        return isHealthy -> {
            for (Entry<StreamObserver<HealthCheckResponse>, Entry<String, ServingStatus>> entry
                    : watchers.entrySet()) {
                final ServingStatus previousStatus = entry.getValue().getValue();
                final String serviceName = entry.getValue().getKey();
                final ServingStatus currentStatus = checkServingStatus(serviceName);
                if (currentStatus != previousStatus) {
                    final StreamObserver<HealthCheckResponse> responseObserver = entry.getKey();
                    responseObserver.onNext(HealthCheckResponse.newBuilder()
                                                               .setStatus(currentStatus)
                                                               .build());
                    entry.setValue(Maps.immutableEntry(serviceName, currentStatus));
                }
            }
        };
    }

    private boolean isHealthy() {
        for (HealthChecker healthChecker : healthCheckers) {
            if (!healthChecker.isHealthy()) {
                return false;
            }
        }
        return true;
    }
}
