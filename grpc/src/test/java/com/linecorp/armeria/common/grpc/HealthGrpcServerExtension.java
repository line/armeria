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

import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.protobuf.services.HealthStatusManager;

public class HealthGrpcServerExtension extends ServerExtension {

    private final HealthStatusManager healthStatusManager = new HealthStatusManager();

    @Override
    protected void configure(ServerBuilder sb) throws Exception {
        final GrpcService grpcService = GrpcService.builder()
                .addService(healthStatusManager.getHealthService())
                .build();

        sb.service(grpcService);
    }

    public void setStatus(HealthCheckResponse.ServingStatus status) {
        healthStatusManager.setStatus(HealthStatusManager.SERVICE_NAME_ALL_SERVICES, status);
    }

    public void clearStatus() {
        healthStatusManager.clearStatus(HealthStatusManager.SERVICE_NAME_ALL_SERVICES);
    }

    public void enterTerminalState() {
        healthStatusManager.enterTerminalState();
    }
}
