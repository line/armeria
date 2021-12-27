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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerListenerAdapter;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.ServiceRequestContext;
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
@UnstableApi
public final class GrpcHealthCheckService extends HealthImplBase {

    private static final Logger logger = LoggerFactory.getLogger(GrpcHealthCheckService.class);

    private static final HealthCheckResponse SERVING_RESPONSE =
            HealthCheckResponse.newBuilder()
                               .setStatus(ServingStatus.SERVING)
                               .build();

    private static final HealthCheckResponse NOT_SERVING_RESPONSE =
            HealthCheckResponse.newBuilder()
                               .setStatus(ServingStatus.NOT_SERVING)
                               .build();

    private static final HealthCheckResponse SERVICE_UNKNOWN_RESPONSE =
            HealthCheckResponse.newBuilder()
                               .setStatus(ServingStatus.SERVICE_UNKNOWN)
                               .build();

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

    private static HealthCheckResponse getHealthCheckResponse(ServingStatus status) {
        if (status == ServingStatus.SERVING) {
            return SERVING_RESPONSE;
        } else if (status == ServingStatus.NOT_SERVING) {
            return NOT_SERVING_RESPONSE;
        } else if (status == ServingStatus.SERVICE_UNKNOWN) {
            return SERVICE_UNKNOWN_RESPONSE;
        } else {
            throw new IllegalArgumentException("Invalid status:" + status);
        }
    }

    private static void setInternalHealthUpdateListener(
            HealthCheckUpdateListener healthCheckUpdateListener,
            Collection<ListenableHealthChecker> listenableHealthCheckers) {
        listenableHealthCheckers.forEach(lhc -> {
            lhc.addListener(healthChecker -> {
                try {
                    healthCheckUpdateListener.healthUpdated(healthChecker.isHealthy());
                } catch (Throwable t) {
                    logger.warn("Unexpected exception from HealthCheckUpdateListener.healthUpdated():", t);
                }
            });
        });
    }

    private final SettableHealthChecker serverHealth;
    private final Set<ListenableHealthChecker> serverHealthCheckers;
    private final Map<String, ListenableHealthChecker> grpcServiceHealthCheckers;
    private final IdentityHashMap<StreamObserver<HealthCheckResponse>, Entry<String, ServingStatus>>
            watchers = new IdentityHashMap<>();
    @Nullable
    private Server server;

    GrpcHealthCheckService(
            Set<ListenableHealthChecker> serverHealthCheckers,
            Map<String, ListenableHealthChecker> grpcServiceHealthCheckers,
            List<HealthCheckUpdateListener> updateListeners
    ) {
        serverHealth = new SettableHealthChecker(false);
        if (!updateListeners.isEmpty()) {
            addServerHealthUpdateListener(updateListeners);
        }
        this.serverHealthCheckers = ImmutableSet.<ListenableHealthChecker>builder()
                                                .add(serverHealth)
                                                .addAll(serverHealthCheckers)
                                                .build();
        this.grpcServiceHealthCheckers = grpcServiceHealthCheckers;
        final HealthCheckUpdateListener healthCheckUpdateListener = provideInternalHealthUpdateListener();
        setInternalHealthUpdateListener(healthCheckUpdateListener, this.serverHealthCheckers);
        setInternalHealthUpdateListener(healthCheckUpdateListener, this.grpcServiceHealthCheckers.values());
    }

    @Override
    public void check(HealthCheckRequest request, StreamObserver<HealthCheckResponse> responseObserver) {
        final String service = request.getService();
        final ServingStatus status = checkServingStatus(service);
        if (status == ServingStatus.SERVICE_UNKNOWN) {
            responseObserver.onError(Status.NOT_FOUND
                                             .withDescription(String.format(
                                                     "The service name(%s) is not registered in this service",
                                                     service))
                                             .asRuntimeException());
            return;
        }
        responseObserver.onNext(getHealthCheckResponse(status));
        responseObserver.onCompleted();
    }

    @Override
    public void watch(HealthCheckRequest request, StreamObserver<HealthCheckResponse> responseObserver) {
        ServiceRequestContext.current().clearRequestTimeout();
        final String service = request.getService();
        synchronized (watchers) {
            final ServingStatus status = checkServingStatus(service);
            final HealthCheckResponse response = getHealthCheckResponse(status);
            responseObserver.onNext(response);
            watchers.put(responseObserver, Maps.immutableEntry(service, response.getStatus()));
        }
        ((ServerCallStreamObserver<HealthCheckResponse>) responseObserver).setOnCancelHandler(() -> {
            synchronized (watchers) {
                watchers.remove(responseObserver);
            }
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
                // NOT_SERVING will be sent to clients by changing a healthiness of a server
                serverHealth.setHealthy(false);
                synchronized (watchers) {
                    watchers.keySet().forEach(StreamObserver::onCompleted);
                    watchers.clear();
                }
            }
        });
    }

    @VisibleForTesting
    ServingStatus checkServingStatus(@Nullable String serviceName) {
        if (!isServerHealthy()) {
            return ServingStatus.NOT_SERVING;
        }
        if (Strings.isNullOrEmpty(serviceName)) {
            return ServingStatus.SERVING;
        }
        final ListenableHealthChecker listenableHealthChecker = grpcServiceHealthCheckers.get(serviceName);
        if (listenableHealthChecker == null) {
            return ServingStatus.SERVICE_UNKNOWN;
        }
        if (listenableHealthChecker.isHealthy()) {
            return ServingStatus.SERVING;
        }
        return ServingStatus.NOT_SERVING;
    }

    @VisibleForTesting
    void changeServerStatus(boolean isHealthy) {
        serverHealth.setHealthy(isHealthy);
    }

    private void addServerHealthUpdateListener(List<HealthCheckUpdateListener> updateListeners) {
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

    private HealthCheckUpdateListener provideInternalHealthUpdateListener() {
        return isHealthy -> {
            synchronized (watchers) {
                for (Entry<StreamObserver<HealthCheckResponse>, Entry<String, ServingStatus>> entry
                        : watchers.entrySet()) {
                    final ServingStatus previousStatus = entry.getValue().getValue();
                    final String serviceName = entry.getValue().getKey();
                    final ServingStatus currentStatus = checkServingStatus(serviceName);
                    if (currentStatus != previousStatus) {
                        final StreamObserver<HealthCheckResponse> responseObserver = entry.getKey();
                        responseObserver.onNext(getHealthCheckResponse(currentStatus));
                        entry.setValue(Maps.immutableEntry(serviceName, currentStatus));
                    }
                }
            }
        };
    }

    private boolean isServerHealthy() {
        for (HealthChecker healthChecker : serverHealthCheckers) {
            if (!healthChecker.isHealthy()) {
                return false;
            }
        }
        return true;
    }
}
