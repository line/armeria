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
package com.linecorp.armeria.common.grpc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.TextFormat;

import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthGrpc;
import io.grpc.stub.StreamObserver;

public class HealthGrpcServerExtension extends ServerExtension {

    private static final Logger LOGGER = LoggerFactory.getLogger(HealthGrpcServerExtension.class);

    private static final HealthCheckResponse HEALTHY_HEALTH_CHECK_RESPONSE = HealthCheckResponse.newBuilder()
            .setStatus(HealthCheckResponse.ServingStatus.SERVING)
            .build();

    private static final HealthCheckResponse UNHEALTHY_HEALTH_CHECK_RESPONSE = HealthCheckResponse.newBuilder()
            .setStatus(HealthCheckResponse.ServingStatus.NOT_SERVING)
            .build();

    public enum Action {
        RESPOND_HEALTHY, RESPOND_UNHEALTHY, TIMEOUT
    }

    private Action action;

    @Override
    protected void configure(ServerBuilder sb) throws Exception {
        final GrpcService grpcService = GrpcService.builder()
                .addService(new HealthGrpc.HealthImplBase() {
                    @Override
                    public void check(HealthCheckRequest request,
                                      StreamObserver<HealthCheckResponse> responseObserver) {
                        LOGGER.debug("Received health check request {}", TextFormat.shortDebugString(request));

                        if (action == Action.RESPOND_HEALTHY) {
                            responseObserver.onNext(HEALTHY_HEALTH_CHECK_RESPONSE);
                            responseObserver.onCompleted();
                            LOGGER.debug("Sent healthy health check response");
                        } else if (action == Action.RESPOND_UNHEALTHY) {
                            responseObserver.onNext(UNHEALTHY_HEALTH_CHECK_RESPONSE);
                            responseObserver.onCompleted();
                            LOGGER.debug("Sent unhealthy health check response");
                        } else if (action == Action.TIMEOUT) {
                            LOGGER.debug("Not sending a response...");
                        }
                    }

                    @Override
                    public void watch(HealthCheckRequest request,
                                      StreamObserver<HealthCheckResponse> responseObserver) {
                        throw new UnsupportedOperationException();
                    }
                })
                .build();

        sb.service(grpcService);
    }

    public void setAction(Action action) {
        this.action = action;
    }
}
