/*
 * Copyright 2020 LINE Corporation
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
import static org.awaitility.Awaitility.await;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.endpoint.DynamicEndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.healthcheck.HealthCheckService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

/**
 * When {@link EndpointGroup#orElse(EndpointGroup)} is used and the endpoint group is wrapped by
 * {@link HealthCheckedEndpointGroup}, there was a chance that endpoints are not set forever
 * before https://github.com/line/armeria/pull/3181 is merged.
 * It happens when:
 * - {@link EndpointGroup#whenReady()} is completed with the second endpoint group.
 * - The endpoint group of the  first {@link EndpointGroup} are set.
 * - {@link HealthCheckedEndpointGroup} is created.
 *
 * In this case, the health check requests are sent to the second {@link EndpointGroup} to filter
 * unhealthy endpoints. However, because {@link EndpointGroup#endpoints()} returns the endpoint
 * group in the first {@link EndpointGroup}, there's always no matching.
 */
class HealthCheckEndpointGroupRaceTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/", (ctx, req) -> HttpResponse.of(200));
            sb.service("/health", HealthCheckService.of());
        }
    };

    @Test
    void endpointsIsSetBeforeHealthCheckedEndpointGroupIsCreated() {
        final ServerEndpointGroup serverEndpointGroup = new ServerEndpointGroup();
        final EndpointGroup orElse =
                serverEndpointGroup.orElse(EndpointGroup.of(Endpoint.of("unused.com", 8081)));
        serverEndpointGroup.setEndpoint(ImmutableList.of(Endpoint.of("127.0.0.1", server.httpPort())));

        final HealthCheckedEndpointGroup healthCheckedEndpointGroup =
                HealthCheckedEndpointGroup.of(orElse, "/health");
        final WebClient client = WebClient.of(SessionProtocol.HTTP, healthCheckedEndpointGroup);
        await().atMost(2, TimeUnit.SECONDS).until(() -> !healthCheckedEndpointGroup.endpoints().isEmpty());
        assertThat(client.get("/").aggregate().join().status()).isSameAs(HttpStatus.OK);
    }

    private static class ServerEndpointGroup extends DynamicEndpointGroup {
        void setEndpoint(Iterable<Endpoint> endpoints) {
            setEndpoints(endpoints);
        }
    }
}
