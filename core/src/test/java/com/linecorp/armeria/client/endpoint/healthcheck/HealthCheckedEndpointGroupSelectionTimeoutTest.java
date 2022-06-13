/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.client.endpoint.healthcheck;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.endpoint.healthcheck.HealthCheckedEndpointGroupTest.MockEndpointGroup;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class HealthCheckedEndpointGroupSelectionTimeoutTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/health", (ctx, req) -> HttpResponse.of("OK"));
        }
    };

    @Test
    void shouldSwitchSelectionTimeoutAfterInitialization() {
        try (MockEndpointGroup delegate = new MockEndpointGroup(3000);
             HealthCheckedEndpointGroup endpointGroup =
                     HealthCheckedEndpointGroup.builder(delegate, "/health")
                                               .selectionTimeout(Duration.ofSeconds(10), Duration.ofSeconds(5))
                                               .build()) {
            assertThat(endpointGroup.selectionTimeoutMillis()).isEqualTo(13000);
            delegate.set(server.httpEndpoint());
            endpointGroup.whenReady().join();
            assertThat(endpointGroup.selectionTimeoutMillis()).isEqualTo(8000);
        }
    }
}
