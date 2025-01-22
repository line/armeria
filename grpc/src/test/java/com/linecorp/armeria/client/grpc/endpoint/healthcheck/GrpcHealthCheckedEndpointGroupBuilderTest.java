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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.endpoint.healthcheck.HealthCheckedEndpointGroup;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.grpc.HealthGrpcServerExtension;

class GrpcHealthCheckedEndpointGroupBuilderTest {

    @RegisterExtension
    private static HealthGrpcServerExtension serverExtension = new HealthGrpcServerExtension();

    @Test
    public void hasHealthyEndpoint() {
        serverExtension.setAction(HealthGrpcServerExtension.Action.DO_HEALTHY);

        final HealthCheckedEndpointGroup endpointGroup = GrpcHealthCheckedEndpointGroupBuilder
                .builder(serverExtension.endpoint(SessionProtocol.H2C))
                .build();

        assertThat(endpointGroup.whenReady().join()).hasSize(1);
    }

    @Test
    public void empty() throws Exception {
        serverExtension.setAction(HealthGrpcServerExtension.Action.DO_UNHEALTHY);

        final HealthCheckedEndpointGroup endpointGroup = GrpcHealthCheckedEndpointGroupBuilder
                .builder(serverExtension.endpoint(SessionProtocol.H2C))
                .build();

        assertThatThrownBy(() -> {
            // whenReady() will timeout because there are no healthy endpoints
            endpointGroup.whenReady().get(1, TimeUnit.SECONDS);
        }).isInstanceOf(TimeoutException.class);
    }
}
